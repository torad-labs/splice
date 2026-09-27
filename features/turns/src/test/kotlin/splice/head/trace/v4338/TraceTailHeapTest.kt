// NEW: V4-338 — `splice trace <head> --last N --json`, and the console's GET /api/heads/{head}/trace, answer
// from a trace store larger than the heap they run in, holding one line at a time and the records of the
// turns they answer with. Both read every record of every day into memory first, and on claudex's 3.7 GB
// of day files (2026-09-26, lines of 2-3 MB: every record carries the whole conversation) the CLI died
// with java.lang.OutOfMemoryError in kotlinx's JsonTreeReader; the route runs inside the daemon. The
// store here is written by the daemon's own TraceStore, and each reader runs in a second JVM whose heap
// is smaller than the store.
package splice.head.trace.v4338

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
import splice.core.util.AsyncFileIo
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceCommand
import splice.head.trace.TraceHeads
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.upstream.sse.WireAttempt
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val HEAD = "claudex"
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val DAY_MS = 86_400_000L

// why: each body is a megabyte, so a turn's two records are about 2 MB each, the size claudex's are live
private const val BODY_CHARS = 1 shl 20

// why: the heap each reader runs with here; the store below is about 88 MB of records, past it
private const val CHILD_HEAP = "-Xmx64m"
private const val CHILD_SECONDS = 120L

// why: the store's turns: ten on day one, ten in day two's rolled half and two in its live file
private const val TURNS_ON_DISK = 22

class TraceTailHeapTest {

    private fun meta() = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "gpt-6-sol",
        upstreamModel = "gpt-6-sol",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = "0f0eef86-f7d8-4170-a758-8b5ec461e250",
    )

    /** Writes [ids] as the daemon would, one attempt and one turn record each, on the day [at] falls in. */
    private fun write(traceDir: Path, at: Long, ids: List<String>) {
        val queue = ArrayDeque(ids)
        val store = TraceStore(
            ActivityDays(traceDir, HEAD, 30, WallClock { at }, true),
            HEAD,
            maxBodyChars = 2 * BODY_CHARS,
            now = WallClock { at },
            ids = TurnIdMint { queue.removeFirst() },
        )
        ids.forEach { id ->
            val body = id.padEnd(BODY_CHARS, 'x')
            val trace = store.begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), body))
            trace.responseText(body)
            trace.attempted(
                WireAttempt(
                    attempt = 1,
                    url = "https://chatgpt.com/backend-api/codex/responses",
                    requestHeaders = emptyMap(),
                    requestBody = body,
                    requestEncoding = null,
                    status = 200,
                    responseHeaders = emptyMap(),
                    errorText = null,
                    failure = null,
                    durationMs = 40,
                ),
            )
            trace.clientFrame(body)
            trace.finish("ok", PerfSnapshot(mapOf("total" to 120L), emptyMap()))
            assertTrue(AsyncFileIo.drain(), "the file lane drained")
        }
    }

    /** Day one's ten turns, then day two rolled once as a busy day does: its older ten turns in the rolled
     *  half, d2-10 and d2-11 in the live file. The newest three cross from the live file into the rolled half. */
    private fun store(tmp: Path): Path {
        val traceDir = tmp.resolve("trace")
        write(traceDir, DAY_ONE, (0 until 10).map { "d1-%02d".format(it) })
        val dayTwo = DAY_ONE + DAY_MS
        write(traceDir, dayTwo, (0 until 10).map { "d2-%02d".format(it) })
        val live = traceDir.resolve("$HEAD-2026-09-19.jsonl")
        Files.move(live, live.resolveSibling("${live.fileName}.1"))
        write(traceDir, dayTwo, listOf("d2-10", "d2-11"))
        return traceDir
    }

    /** Runs [reader] of [TraceTailProbe] in a JVM at [CHILD_HEAP] over [traceDir]: its exit, stdout, stderr. */
    private fun child(tmp: Path, reader: String, traceDir: Path): Triple<Int, List<String>, String> {
        val stdout = tmp.resolve("$reader.stdout")
        val stderr = tmp.resolve("$reader.stderr")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            CHILD_HEAP,
            "-cp",
            System.getProperty("java.class.path"),
            TraceTailProbe::class.java.name,
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
    fun `--last 3 --json answers from a store larger than the verb's heap, with the newest three turns`(
        @TempDir tmp: Path,
    ) {
        val (exit, out, err) = child(tmp, "verb", store(tmp))

        assertEquals(0, exit, "splice trace $HEAD --last 3 --json failed: ${err.take(600)}")
        val records = out.filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            listOf("d2-09", "d2-09", "d2-10", "d2-10", "d2-11", "d2-11"),
            records.map { it.getValue("turn").jsonPrimitive.content },
        )
        assertEquals(List(3) { listOf("attempt", "turn") }.flatten(), records.map { it.str("kind") })
    }

    @Test
    fun `the console's list answers from a store larger than the daemon's heap, counting every turn on disk`(
        @TempDir tmp: Path,
    ) {
        val (exit, out, err) = child(tmp, "route", store(tmp))

        assertEquals(0, exit, "GET /api/heads/$HEAD/trace?last=3 failed: ${err.take(600)}")
        assertEquals("200", out.first(), out.first())
        val payload = Json.parseToJsonElement(out[1]).jsonObject
        assertEquals(TURNS_ON_DISK.toString(), payload.str("on_disk"))
        assertEquals("0", payload.str("skipped_lines"))
        assertEquals(
            listOf("d2-09", "d2-10", "d2-11"),
            payload.getValue("turns").jsonArray.map { it.jsonObject.str("id") },
        )
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
}

/** Run in a second JVM by the tests above, with a heap smaller than the store in args[1]. args[0] names the
 *  reader: `verb` is `splice trace claudex --last 3 --json`, its records on stdout; `route` is the console's
 *  GET /api/heads/claudex/trace?last=3, its status then its body on stdout. */
object TraceTailProbe {
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
    ).trace(listOf(HEAD, "--last", "3", "--json"), EnvReader { null })

    private fun route(traceDir: Path): Boolean {
        val noCompaction = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { name -> if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction)) else emptyList() }
        val route = TraceRoute(heads, { traceDir }, Dispatchers.IO)
        val reply = runBlocking { route.read(HEAD, TraceQuery("3", null, null)) }
        println(reply.status.value)
        println(reply.body)
        return reply.status == HttpStatusCode.OK
    }
}
