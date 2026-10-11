// the count of turns on disk never decodes a record's body: the console's trace list and the
// verb's table count a store holding one record larger than the heap they run in. The count decoded every
// line whole, into a String and then through kotlinx, and on claudex's 3.6 GB store (2026-09-26, lines of
// 2-3 MB) that decode was 83% of GET /api/heads/claudex/trace's 21.4 s; a record past the heap was an
// OutOfMemoryError. The big record is written here in the daemon's field order, its stamp first; the turns
// listed are day two's, written by the daemon's own TraceStore, so the big one is counted and never held.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.terminal.TerminalOutput
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.AsyncFileIo
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.ClientInbound
import splice.head.wire.TurnIdMint
import splice.upstream.sse.WireAttempt
import splice.upstream.sse.WireRequest
import splice.upstream.sse.WireResponse
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val HEAD = "claudex"
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val DAY_MS = 86_400_000L

// why: the big record's body, half again the heap below, so a reader that decodes it whole cannot hold it
private const val BIG_BODY_BYTES = 96L shl 20

// why: the bytes written per call while the big body streams out, so the test's own heap never holds it
private const val CHUNK_BYTES = 1 shl 16

// why: the heap each reader runs with here
private const val CHILD_HEAP = "-Xmx64m"
private const val CHILD_SECONDS = 120L

// why: the turns on disk: the big one on day one, three on day two
private const val TURNS_ON_DISK = 4

class TraceCensusHeapTest {

    private fun meta() = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.TEXT,
            effort = "medium",
            summary = null,
            budgetTokens = null,
        ),
        route = TurnRoute(
            stream = true,
            originalModel = "gpt-6-sol",
            upstreamModel = "gpt-6-sol",
            clientMaxTokens = 8000,
        ),
        scope = TurnScope(sessionId = "0f0eef86-f7d8-4170-a758-8b5ec461e250"),
    )

    /** Day one: the big turn, its attempt record [BIG_BODY_BYTES] of body behind its stamp, then its turn record. */
    private fun bigDay(traceDir: Path) {
        Files.createDirectories(traceDir)
        val stamp = """{"kind":"attempt","turn":"big","ts":$DAY_ONE,"head":"$HEAD","session":"0f0eef86",""" +
            """"model":"gpt-6-sol","round":1,"attempt":1,"transport":"http","request":{"headers":{},"body":""""
        val tail = """","truncated":false},"response":{"text":"","truncated":false},"durationMs":40}""" + "\n" +
            """{"kind":"turn","turn":"big","ts":${DAY_ONE + 1_000},"head":"$HEAD","session":"0f0eef86",""" +
            """"model":"gpt-6-sol","outcome":"ok","rounds":"1","attempts":"1"}""" + "\n"
        val chunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        BufferedOutputStream(Files.newOutputStream(traceDir.resolve("$HEAD-2026-09-18.jsonl"))).use { out ->
            out.write(stamp.toByteArray())
            var left = BIG_BODY_BYTES
            while (left > 0) {
                val n = minOf(left, CHUNK_BYTES.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
            out.write(tail.toByteArray())
        }
    }

    /** Day two: [ids] as the daemon writes them, one attempt and one turn record each. */
    private fun dayTwo(traceDir: Path, ids: List<String>) {
        val at = DAY_ONE + DAY_MS
        val queue = ArrayDeque(ids)
        val store = splice.head.syntheticTraceStore(
            ActivityDays(traceDir, HEAD, 30, WallClock { at }, true),
            HEAD,
            maxBodyChars = 1 shl 16,
            now = WallClock { at },
            ids = TurnIdMint { queue.removeFirst() },
        )
        ids.forEach { id ->
            val trace = store.begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), id))
            val url = "https://chatgpt.com/backend-api/codex/responses"
            trace.attempted(
                WireAttempt(1, WireRequest(url, emptyMap(), id, null), WireResponse(200, emptyMap(), null), null, 40),
            )
            trace.finish("ok", PerfSnapshot(mapOf("total" to 120L), emptyMap()))
            assertTrue(AsyncFileIo.drain(), "the file lane drained")
        }
    }

    private fun store(tmp: Path): Path {
        val traceDir = tmp.resolve("trace")
        bigDay(traceDir)
        dayTwo(traceDir, listOf("d2-00", "d2-01", "d2-02"))
        return traceDir
    }

    /** Runs [reader] of [CensusHeapProbe] in a JVM at [CHILD_HEAP] over [traceDir]: its exit, stdout, stderr. */
    private fun child(tmp: Path, reader: String, traceDir: Path): Triple<Int, List<String>, String> {
        val stdout = tmp.resolve("$reader.stdout")
        val stderr = tmp.resolve("$reader.stderr")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            CHILD_HEAP,
            "-cp",
            System.getProperty("java.class.path"),
            CensusHeapProbe::class.java.name,
            reader,
            traceDir.toString(),
        ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "$reader did not answer in $CHILD_SECONDS s")
        } finally {
            // A reader that never answers is not left running past its verdict.
            process.destroyForcibly()
        }
        return Triple(process.exitValue(), Files.readAllLines(stdout), Files.readString(stderr))
    }

    @Test
    fun `the console's list counts a record larger than the daemon's heap without decoding it`(@TempDir tmp: Path) {
        val (exit, out, err) = child(tmp, "route", store(tmp))

        assertEquals(0, exit, "GET /api/heads/$HEAD/trace?last=2 failed: ${err.take(600)}")
        assertEquals("200", out.first(), out.first())
        val payload = Json.parseToJsonElement(out[1]).jsonObject
        assertEquals(TURNS_ON_DISK.toString(), payload.str("on_disk"))
        assertEquals("0", payload.str("skipped_lines"))
        assertEquals(listOf("d2-01", "d2-02"), payload.getValue("turns").jsonArray.map { it.jsonObject.str("id") })
    }

    @Test
    fun `the verb's table counts a record larger than its heap without decoding it`(@TempDir tmp: Path) {
        val (exit, out, err) = child(tmp, "verb", store(tmp))

        assertEquals(0, exit, "splice trace $HEAD --last 2 failed: ${err.take(600)}")
        val table = out.joinToString("\n")
        assertTrue(table.contains("2 of $TURNS_ON_DISK turn(s) on disk"), table)
        assertTrue(table.contains("d2-01") && table.contains("d2-02"), table)
        assertTrue(!table.contains("big"), "the big turn is counted, not listed: $table")
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
}

/** Run in a second JVM by the tests above, with a heap smaller than the big record, over the trace dir in
 *  args[1]. args[0] names the reader: `verb` is `splice trace claudex --last 2`, its table on stdout; `route` is
 *  the console's GET /api/heads/claudex/trace?last=2, its status then its body on stdout. */
object CensusHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val traceDir = Path.of(args[1])
        val ok = if (args[0] == "verb") verb(traceDir) else route(traceDir)
        System.out.flush()
        exitProcess(if (ok) 0 else 1)
    }

    private fun verb(traceDir: Path): Boolean = TraceCommand(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        heads = { TraceHeads.Configured("splice.toml", setOf(HEAD)) },
        traceDirs = { traceDir },
        heap = splice.head.syntheticHeapBudget(),
    ).trace(listOf(HEAD, "--last", "2"), EnvReader { null })

    private fun route(traceDir: Path): Boolean {
        val noCompaction = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { name -> if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction)) else emptyList() }
        val route = TraceRoute(heads, { traceDir }, Dispatchers.IO, heap = splice.head.syntheticHeapBudget())
        val reply = runBlocking { route.read(HEAD, TraceQuery("2", null, null)) }
        println(reply.status.value)
        println(reply.body)
        return reply.status == HttpStatusCode.OK
    }
}
