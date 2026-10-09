// `splice trace <head> --last N --json`, and the console's GET /api/heads/{head}/trace, answer
// from a trace store larger than the heap they run in, holding one line at a time and the records of the
// turns they answer with. Both read every record of every day into memory first, and on claudex's 3.7 GB
// of day files (2026-09-26, lines of 2-3 MB: every record carries the whole conversation) the CLI died
// with java.lang.OutOfMemoryError in kotlinx's JsonTreeReader; the route runs inside the daemon. The
// store here is written by the daemon's own TraceStore, and each reader runs in a second JVM whose heap
// is smaller than the store.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.storage.DayBodyBudget
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
import splice.head.trace.body.TraceBodyPack
import splice.head.trace.body.TracePackIndex
import splice.head.wire.ClientInbound
import splice.head.wire.TurnIdMint
import splice.upstream.sse.WireAttempt
import splice.upstream.sse.WireRequest
import splice.upstream.sse.WireResponse
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

    /** Writes [ids] as the daemon would, one attempt and one turn record each, on the day [at] falls in. */
    private fun write(traceDir: Path, at: Long, ids: List<String>, bodyChars: Int = BODY_CHARS) {
        val queue = ArrayDeque(ids)
        val store = splice.head.syntheticTraceStore(
            ActivityDays(traceDir, HEAD, 30, WallClock { at }, true),
            HEAD,
            maxBodyChars = 2 * bodyChars,
            now = WallClock { at },
            ids = TurnIdMint { queue.removeFirst() },
        )
        ids.forEach { id ->
            val body = id.padEnd(bodyChars, 'x')
            val trace = store.begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), body))
            trace.responseText(body)
            trace.attempted(
                WireAttempt(
                    attempt = 1,
                    request = WireRequest(
                        url = "https://chatgpt.com/backend-api/codex/responses",
                        headers = emptyMap(),
                        body = body,
                        encoding = null,
                    ),
                    response = WireResponse(status = 200, headers = emptyMap(), errorText = null),
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

    @Test
    fun `twenty multi megabyte turns list within 64 MiB without changing their summaries`(@TempDir tmp: Path) {
        val traceDir = tmp.resolve("bounded")
        val ids = (0 until 20).map { "bounded-%02d".format(it) }
        write(traceDir, DAY_ONE, ids, bodyChars = 2 * BODY_CHARS)
        val ask = TraceAsk(last = ids.size)
        val expected = ids.map { id ->
            val rows = TraceRows(heap = splice.head.syntheticHeapBudget())
            TraceTurnSummary.of(rows.turns(traceDir, HEAD, TraceAsk(last = 1, turn = id)).single()).toString()
        }
        val heap = HeapBudget(heapLimitBytes = 1L shl 30, budgetBytes = 64L shl 20)
        assertEquals(64L shl 20, heap.limitBytes)

        val listed = TraceRows(heap = heap).summaries(traceDir, HEAD, ask)

        assertEquals(ids.size, listed.onDisk)
        assertEquals(0, listed.skippedLines)
        assertEquals(0, listed.unavailableRecords)
        assertEquals(expected, listed.turns.map { TraceTurnSummary.of(it).toString() })
        val wide = TraceRows(heap = HeapBudget(heapLimitBytes = 1L shl 30, budgetBytes = 64L shl 20))
            .summaries(traceDir, HEAD, TraceAsk(last = 2000))
        assertEquals(expected, wide.turns.map { TraceTurnSummary.of(it).toString() })
        assertEquals(listed.onDisk, wide.onDisk)
        listed.turns.forEach { turn ->
            (turn.attempts + listOfNotNull(turn.turn)).forEach { record ->
                listOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")
                    .forEach { (section, field) ->
                        val body = record[section]?.jsonObject?.get(field)
                        assertTrue(body == null || body.toString() == "null", "list retained $section.$field")
                    }
            }
        }
        assertThrows(HeapCapacityException::class.java) {
            TraceRows(heap = HeapBudget(heapLimitBytes = 1L shl 30, budgetBytes = 64L shl 20))
                .read(traceDir, HEAD, ask)
        }
    }

    @Test
    fun `summary validation rejects well hashed chunks that are not a complete JSON string`(@TempDir tmp: Path) {
        val day = tmp.resolve("$HEAD-2026-09-18.jsonl")
        val invalid = listOf("false", "\"unfinished", "\"bad\\q\"", "\"first\" \"second\"")
        val records = TraceBodyPack(
            day.resolveSibling("${day.fileName}.bodies2"),
            TracePackIndex(heap = splice.head.syntheticHeapBudget()),
            budget = DayBodyBudget(1L shl 20, minFreeBytes = 0),
        ).use { pack ->
            invalid.mapIndexed { index, literal ->
                val parts = literal.toByteArray().asList().chunked(3).map { pack.put(it.toByteArray()) }
                buildJsonObject {
                    put("kind", "turn")
                    put("turn", "invalid-$index")
                    put("ts", DAY_ONE)
                    putJsonObject("client") {
                        putJsonObject("body") {
                            put("trace_chunks", 2)
                            put("parts", JsonArray(parts))
                        }
                    }
                }
            }
        }
        Files.writeString(day, records.joinToString("\n", postfix = "\n"))
        val ask = TraceAsk(last = invalid.size)
        val original = TraceRows(heap = splice.head.syntheticHeapBudget()).read(tmp, HEAD, ask)
        assertEquals(invalid.size, original.unavailableRecords)

        val listed = TraceRows(heap = splice.head.syntheticHeapBudget()).summaries(tmp, HEAD, ask)

        assertEquals(original.unavailableRecords, listed.unavailableRecords)
        assertEquals(
            original.turns.map { TraceTurnSummary.of(it).toString() },
            listed.turns.map { TraceTurnSummary.of(it).toString() },
        )
    }

    @Test
    fun `legacy inline bodies are discarded and only projected metadata remains charged`(@TempDir tmp: Path) {
        val day = tmp.resolve("$HEAD-2026-09-18.jsonl")
        Files.newBufferedWriter(day).use { writer ->
            repeat(20) { index ->
                val id = "inline-$index"
                val attempt = buildJsonObject {
                    put("kind", "attempt")
                    put("turn", id)
                    put("ts", DAY_ONE)
                    put("model", "synthetic")
                    put("attempt", 1)
                    putJsonObject("request") { put("body", id.padEnd(2 * BODY_CHARS, 'x')) }
                }
                val ending = buildJsonObject {
                    put("kind", "turn")
                    put("turn", id)
                    put("ts", DAY_ONE)
                    put("model", "synthetic")
                    put("outcome", "failure:api_error")
                    put("failure_sentence", "synthetic stored refusal")
                    put("attempts", 1)
                    put("rounds", 1)
                }
                writer.appendLine(attempt.toString())
                writer.appendLine(ending.toString())
            }
        }
        val ask = TraceAsk(last = 20)
        val original = TraceRows(heap = splice.head.syntheticHeapBudget()).read(tmp, HEAD, ask)
        val heap = HeapBudget(heapLimitBytes = 1L shl 30, budgetBytes = 64L shl 20)

        val listed = TraceRows(heap = heap).summaries(tmp, HEAD, ask)

        assertEquals(20, listed.onDisk)
        assertEquals(
            original.turns.map { TraceTurnSummary.of(it).toString() },
            listed.turns.map { TraceTurnSummary.of(it).toString() },
        )
        assertTrue(listed.turns.all { it.attempts.single()["request"]?.jsonObject?.get("body").toString() == "null" })
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
        heap = splice.head.syntheticHeapBudget(),
    ).trace(listOf(HEAD, "--last", "3", "--json"), EnvReader { null })

    private fun route(traceDir: Path): Boolean {
        val noCompaction = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { name -> if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction)) else emptyList() }
        val route = TraceRoute(heads, { traceDir }, Dispatchers.IO, heap = splice.head.syntheticHeapBudget())
        val reply = runBlocking { route.read(HEAD, TraceQuery("3", null, null)) }
        println(reply.status.value)
        println(reply.body)
        return reply.status == HttpStatusCode.OK
    }
}
