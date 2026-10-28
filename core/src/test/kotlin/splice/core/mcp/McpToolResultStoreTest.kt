package splice.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpToolResultStoreTest {
    @TempDir lateinit var temporary: Path
    private val arguments = Json.parseToJsonElement("""{"value":0,"flag":false,"empty":[]}""").jsonObject
    private val result = Json.parseToJsonElement(
        """{
            "content":[{"type":"text","text":"visible"}],
            "structuredContent":{"value":0,"flag":false,"empty":[],"nested":{"_meta":"public"}},
            "isError":false,"_meta":{"secret":"retained privately"}
        }""",
    ).jsonObject
    private val paths get() = StatePaths(baseOverride = temporary)

    @Test
    fun `a new reader retains the complete raw value without projecting private or falsy fields`() {
        val id = "toolu_splice_00000000-0000-0000-0000-000000000000"
        val writer = McpToolResultStore(paths)
        assertEquals(
            McpToolResultStore.Capture.Stored,
            writer.capture(id, "source server", "structured.dot", arguments, result),
        )

        val receipt = found(McpToolResultStore(paths).read(id, arguments))
        assertEquals(McpToolResultStore.Outcome.RawResult(result), receipt.outcome)
        assertEquals(arguments, receipt.arguments)
        assertEquals(id, receipt.callbackId)
        assertEquals("source server", receipt.server)
        assertEquals("structured.dot", receipt.tool)
        val file = receiptFiles().single()
        assertEquals("c6282249b6e7f4094515726112ae244d2318aa4ebe39084d94e0fa2f68c790a5.json", file.fileName.toString())
        assertFalse(file.fileName.toString().contains(id), "callback identities never become path segments")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `only an exact owned callback and argument binding may read a raw value`() {
        val id = id()
        val store = McpToolResultStore(paths)
        assertEquals(McpToolResultStore.Capture.Stored, store.capture(id, "server", "structured", arguments, result))
        assertInstanceOf(McpToolResultStore.Read.Refused::class.java, store.read(id, JsonObject(emptyMap())))
        assertInstanceOf(McpToolResultStore.Read.Refused::class.java, store.read("../unowned", arguments))
        assertEquals(McpToolResultStore.Read.Missing, store.read(id(), arguments))
    }

    @Test
    fun `identical retries are idempotent but each differing call component leaves a durable conflict`() {
        val changes = listOf(
            Change(server = "other"),
            Change(tool = "other"),
            Change(arguments = JsonObject(emptyMap())),
            Change(result = JsonObject(result + ("isError" to kotlinx.serialization.json.JsonPrimitive(true)))),
        )
        changes.forEach { change ->
            val id = id()
            val store = McpToolResultStore(paths)
            repeat(2) {
                assertEquals(
                    McpToolResultStore.Capture.Stored,
                    store.capture(id, "server", "structured", arguments, result),
                )
            }
            val changed = store.capture(id, change.server, change.tool, change.arguments, change.result)
            assertInstanceOf(McpToolResultStore.Capture.Refused::class.java, changed)
            assertInstanceOf(
                McpToolResultStore.Read.Refused::class.java,
                McpToolResultStore(paths).read(id, arguments),
            )
            assertInstanceOf(
                McpToolResultStore.Capture.Refused::class.java,
                store.capture(id, "server", "structured", arguments, result),
            )
        }
    }

    @Test
    fun `concurrent reconstructed writers cannot bind one callback to differing results`() {
        val id = id()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0..7).map { value ->
                executor.submit<McpToolResultStore.Capture> {
                    McpToolResultStore(paths).capture(
                        id,
                        "server",
                        "structured",
                        arguments,
                        JsonObject(
                            result + (
                                "structuredContent" to JsonObject(
                                    mapOf("value" to kotlinx.serialization.json.JsonPrimitive(value)),
                                )
                                ),
                        ),
                    )
                }
            }
            assertEquals(1, tasks.count { it.get(10, TimeUnit.SECONDS) == McpToolResultStore.Capture.Stored })
            assertInstanceOf(
                McpToolResultStore.Read.Refused::class.java,
                McpToolResultStore(paths).read(id, arguments),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expiry and item retention return explicit absence while keeping the newest complete values`() {
        var now = 1_000L
        val limits = McpToolResultStore.Limits(
            maxItems = 2,
            maxTotalBytes = 10_000,
            maxEntryBytes = 2_000,
            maxAgeMs = 100,
        )
        val store = McpToolResultStore(paths, WallClock { now }, limits)
        val ids = List(3) { id() }
        ids.forEach { id ->
            assertEquals(
                McpToolResultStore.Capture.Stored,
                store.capture(id, "server", "structured", arguments, result),
            )
            now += 1
        }
        assertEquals(McpToolResultStore.Read.Missing, store.read(ids.first(), arguments))
        assertEquals(
            McpToolResultStore.Outcome.RawResult(result),
            found(store.read(ids.last(), arguments)).outcome,
        )
        assertEquals(2, receiptFiles().size)
        now += 100
        assertEquals(McpToolResultStore.Read.Missing, store.read(ids.last(), arguments))
    }

    @Test
    fun `oversized and malformed values never expose a partial or diagnostic copy of the payload`() {
        val id = id()
        val bounded =
            McpToolResultStore(paths, limits = McpToolResultStore.Limits(maxTotalBytes = 300, maxEntryBytes = 300))
        assertInstanceOf(
            McpToolResultStore.Capture.Refused::class.java,
            bounded.capture(id, "server", "structured", arguments, result),
        )
        assertEquals(McpToolResultStore.Read.Missing, bounded.read(id, arguments))

        val store = McpToolResultStore(paths)
        assertEquals(McpToolResultStore.Capture.Stored, store.capture(id, "server", "structured", arguments, result))
        Files.writeString(receiptFiles().single(), "{\"private-receipt-secret\":this-is-not-json}")
        val refused = assertInstanceOf(McpToolResultStore.Read.Refused::class.java, store.read(id, arguments))
        assertFalse(refused.reason.contains("private-receipt-secret"))
        assertTrue(refused.reason.startsWith("raw MCP receipt unavailable ("))
    }

    @Test
    fun `total bytes remain bounded and abandoned atomic staging files are removed`() {
        var now = 1_000L
        val limits = McpToolResultStore.Limits(maxTotalBytes = 900, maxEntryBytes = 900)
        val store = McpToolResultStore(paths, WallClock { now }, limits)
        val ids = List(3) { id() }
        ids.forEach { id ->
            assertEquals(
                McpToolResultStore.Capture.Stored,
                store.capture(id, "server", "structured", arguments, result),
            )
            now += 1
        }
        val staging = paths.mcpToolResultsDir.resolve(".mcp-result-orphan.tmp")
        Files.writeString(staging, "incomplete private bytes")
        assertEquals(
            McpToolResultStore.Capture.Stored,
            store.capture(ids.last(), "server", "structured", arguments, result),
        )
        assertFalse(Files.exists(staging))
        assertTrue(receiptFiles().sumOf(Files::size) <= limits.maxTotalBytes)
        assertEquals(McpToolResultStore.Read.Missing, store.read(ids.first(), arguments))
        assertEquals(
            McpToolResultStore.Outcome.RawResult(result),
            found(store.read(ids.last(), arguments)).outcome,
        )
    }

    @Test
    fun `a refused oversized or malformed repeat cannot leave the first value readable`() {
        val store =
            McpToolResultStore(paths, limits = McpToolResultStore.Limits(maxTotalBytes = 10_000, maxEntryBytes = 1_000))
        val oversized = JsonObject(result + ("content" to JsonArray(listOf(JsonPrimitive("x".repeat(2_000))))))
        listOf(oversized, JsonObject(emptyMap())).forEach { replacement ->
            val id = id()
            assertEquals(
                McpToolResultStore.Capture.Stored,
                store.capture(id, "server", "structured", arguments, result),
            )
            assertInstanceOf(
                McpToolResultStore.Capture.Refused::class.java,
                store.capture(id, "server", "structured", arguments, replacement),
            )
            assertInstanceOf(
                McpToolResultStore.Read.Refused::class.java,
                McpToolResultStore(paths).read(id, arguments),
            )
        }
    }

    @Test
    fun `a transport error cannot reuse a prior raw receipt and a first error remains absent`() {
        val store = McpToolResultStore(paths)
        val id = id()
        assertInstanceOf(McpToolResultStore.Capture.Refused::class.java, store.invalidate(id))
        assertEquals(McpToolResultStore.Read.Missing, store.read(id, arguments))
        assertEquals(McpToolResultStore.Capture.Stored, store.capture(id, "server", "structured", arguments, result))
        assertInstanceOf(McpToolResultStore.Capture.Refused::class.java, store.invalidate(id))
        assertInstanceOf(
            McpToolResultStore.Read.Refused::class.java,
            McpToolResultStore(paths).read(id, arguments),
        )
    }

    @Test
    fun `a real RPC error retains its typed code message and data without becoming generic text`() {
        val id = id()
        val error = Json.parseToJsonElement(
            """{"code":-32001,"message":"Fixed fixture RPC failure","data":{"flag":false,"value":0,"empty":[]}}""",
        ).jsonObject
        val store = McpToolResultStore(paths)
        assertEquals(
            McpToolResultStore.Capture.Stored,
            store.captureRpcError(id, "server", "structured", arguments, error),
        )
        assertEquals(
            McpToolResultStore.Outcome.RpcError(error),
            found(McpToolResultStore(paths).read(id, arguments)).outcome,
        )
        assertEquals(
            McpToolResultStore.Capture.Stored,
            store.captureRpcError(id, "server", "structured", arguments, error),
        )
        assertInstanceOf(
            McpToolResultStore.Capture.Refused::class.java,
            store.capture(id, "server", "structured", arguments, result),
        )
        assertInstanceOf(
            McpToolResultStore.Read.Refused::class.java,
            McpToolResultStore(paths).read(id, arguments),
        )
    }

    @Test
    fun `a malformed RPC error is absent or poisons an existing result under the same callback`() {
        val error = Json.parseToJsonElement("""{"code":"-32001","message":"invalid code type"}""").jsonObject
        val store = McpToolResultStore(paths)
        val fresh = id()
        assertInstanceOf(
            McpToolResultStore.Capture.Refused::class.java,
            store.captureRpcError(fresh, "server", "structured", arguments, error),
        )
        assertEquals(McpToolResultStore.Read.Missing, store.read(fresh, arguments))
        val existing = id()
        assertEquals(
            McpToolResultStore.Capture.Stored,
            store.capture(existing, "server", "structured", arguments, result),
        )
        assertInstanceOf(
            McpToolResultStore.Capture.Refused::class.java,
            store.captureRpcError(existing, "server", "structured", arguments, error),
        )
        assertInstanceOf(
            McpToolResultStore.Read.Refused::class.java,
            McpToolResultStore(paths).read(existing, arguments),
        )
    }

    private fun id(): String = "toolu_splice_${UUID.randomUUID()}"

    private fun found(read: McpToolResultStore.Read): McpToolResultStore.Receipt =
        assertInstanceOf(McpToolResultStore.Read.Found::class.java, read).receipt

    private fun receiptFiles(): List<Path> = Files.list(paths.mcpToolResultsDir).use { files ->
        files.filter { it.fileName.toString().endsWith(".json") }.toList()
    }

    private inner class Change(
        val server: String = "server",
        val tool: String = "structured",
        val arguments: JsonObject = this@McpToolResultStoreTest.arguments,
        val result: JsonObject = this@McpToolResultStoreTest.result,
    )
}
