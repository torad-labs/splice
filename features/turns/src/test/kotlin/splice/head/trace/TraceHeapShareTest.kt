// NEW: cumulative trace ownership refuses before one read spends the process ledger.
package splice.head.trace

import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.util.JsonWire
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.body.TraceBodies
import splice.http.ingress.HeapIngress
import splice.http.ingress.IngressErrorBody
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

private const val SHARE_RECORDS = 48
private const val SHARE_REPLY_RECORDS = 32
private const val SHARE_BODY_CHARS = 8 * 1024
private const val SHARE_MIB = 1024 * 1024L

class TraceHeapShareTest {
    @Test
    fun `a successful read closes its view while returned records remain charged`(@TempDir dir: Path) {
        write(dir, oneTurn = false)
        val root = HeapBudget(Long.MAX_VALUE, SHARE_MIB)
        val rows = TraceRows(heap = root)
        lateinit var view: HeapBudget
        val turns = rows.withRead { share ->
            view = share
            rows.turns(dir, "synthetic", TraceAsk(1), share)
        }
        assertEquals(1, turns.size)
        assertNull(view.reserve(0), "success closes the one read admission view")
        assertTrue(root.available.value < root.limitBytes, "returned strings are still retained owners")
        java.lang.ref.Reference.reachabilityFence(turns)
    }

    @Test
    fun `a failed read closes its admission view`() {
        val rows = TraceRows(heap = HeapBudget(256, 100))
        lateinit var view: HeapBudget
        assertThrows(IOException::class.java) {
            rows.withRead { share ->
                view = share
                throw IOException("synthetic failed read")
            }
        }
        assertNull(view.reserve(0))
    }

    @Test
    fun `a client cancellation closes its admission view without being swallowed`() {
        val rows = TraceRows(heap = HeapBudget(256, 100))
        lateinit var view: HeapBudget
        assertThrows(CancellationException::class.java) {
            runBlocking {
                rows.withRead { share ->
                    view = share
                    throw CancellationException("synthetic cancelled read")
                }
            }
        }
        assertNull(view.reserve(0))
    }

    @Test
    fun `a partway capacity refusal closes its admission view`(@TempDir dir: Path) {
        write(dir, oneTurn = false)
        val rows = TraceRows(heap = HeapBudget(Long.MAX_VALUE, SHARE_MIB))
        lateinit var view: HeapBudget
        assertThrows(HeapCapacityException::class.java) {
            rows.withRead { share ->
                view = share
                rows.turns(dir, "synthetic", TraceAsk(SHARE_RECORDS), share)
            }
        }
        assertNull(view.reserve(0))
    }

    @Test
    fun `explicit output release restores both the trace domain and process ledger`() {
        val root = HeapBudget(256, 100)
        val rows = TraceRows(heap = root)
        val before = root.available.value
        lateinit var retained: HeapLease
        val output = rows.withRead { share ->
            String(charArrayOf('s', 'y', 'n')).also { retained = HeapOwners.charge(it, share, 40) }
        }
        assertEquals("syn", output)
        assertEquals(before - 40, root.available.value)
        retained.close()
        assertEquals(before, root.available.value)
        rows.withRead { share -> requireNotNull(share.reserve(75)).close() }
        assertEquals(before, root.available.value)
        java.lang.ref.Reference.reachabilityFence(output)
    }

    @Test
    fun `individually admissible literals across packs cannot spend one whole process ledger`(@TempDir dir: Path) {
        write(dir, oneTurn = false)
        val heap = HeapBudget(Long.MAX_VALUE, SHARE_MIB)
        assertThrows(HeapCapacityException::class.java) {
            TraceRows(heap = heap).turns(dir, "synthetic", TraceAsk(SHARE_RECORDS))
        }
        assertTrue(heap.available.value > heap.limitBytes / 4, "refusal leaves capacity for other owners")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a trace reply exceeding its cumulative read share returns the existing capacity response`(
        retainedOutput: Boolean,
        @TempDir dir: Path,
    ) = runBlocking {
        write(dir, oneTurn = true)
        val heap = HeapBudget(Long.MAX_VALUE, 3 * SHARE_MIB)
        val route = route(dir, heap)
        val pending = if (retainedOutput) retainOutput(route) else null
        val ingress = HeapIngress(
            heap,
            4096,
            IngressErrorBody { type, message ->
                JsonWire.string(
                    buildJsonObject {
                        put("type", type)
                        put("message", message)
                    },
                )
            },
        )
        val server = server(ingress, route)
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/trace")).GET().build()
                val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(529, reply.statusCode())
                assertTrue(
                    reply.body().contains("overloaded_error"),
                    "capacity is not an empty trace or corrupt record",
                )
                assertTrue(reply.body().contains("retry"))
                assertFalse(reply.body().contains("invalid_request"))
            }
        } finally {
            server.stop(0, 1000)
            pending?.second?.close()
            java.lang.ref.Reference.reachabilityFence(pending?.first)
        }
    }

    private fun server(ingress: HeapIngress, route: TraceRoute) = embeddedServer(
        Netty,
        serverConfig {
            module {
                ingress.install(this)
                routing {
                    get("/trace") {
                        val reply = route.read("synthetic", TraceQuery(null, null, "synthetic-one"))
                        call.respondText(reply.body, status = reply.status)
                    }
                }
            }
        },
    ) {
        connector {
            host = "127.0.0.1"
            port = 0
        }
        channelPipelineConfig = { pipeline -> ingress.install(pipeline) }
    }

    private fun retainOutput(route: TraceRoute): Pair<String, HeapLease> {
        val rows = route.javaClass.getDeclaredField("rows").apply { isAccessible = true }.get(route) as TraceRows
        val owner = String("synthetic pending output".toCharArray())
        return rows.withRead { share -> owner to HeapOwners.charge(owner, share, share.limitBytes) }
    }

    private fun route(dir: Path, heap: HeapBudget): TraceRoute {
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { listOf(TurnsHead("synthetic", compact)) }
        return TraceRoute(heads, { dir }, Dispatchers.Unconfined).also { route ->
            // Use the existing route and injected row reader on both parent and fixed source.
            route.javaClass.getDeclaredField("rows").apply { isAccessible = true }.set(route, TraceRows(heap = heap))
        }
    }

    private fun write(dir: Path, oneTurn: Boolean) {
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val records = if (oneTurn) SHARE_REPLY_RECORDS else SHARE_RECORDS
        repeat(records) { number ->
            val day = if (oneTurn || number < SHARE_RECORDS / 2) "18" else "19"
            val file = dir.resolve("synthetic-2026-09-$day.jsonl")
            val record = buildJsonObject {
                put("kind", if (oneTurn && number < records - 1) "attempt" else "turn")
                put("turn", if (oneTurn) "synthetic-one" else "synthetic-$number")
                put("ts", 1_789_725_600_000L + number)
                put("client", buildJsonObject { put("body", "synthetic-$number".padEnd(SHARE_BODY_CHARS, 'x')) })
            }
            Files.write(
                file,
                bodies.encode(record, file),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }
}
