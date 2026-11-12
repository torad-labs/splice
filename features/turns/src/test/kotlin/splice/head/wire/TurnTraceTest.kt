// the records one turn writes, read back from the day file: an attempt per send
// (with the response text gathered while that send's stream was consumed), a WebSocket round as
// its own attempt, and the turn record with the client's request, what was streamed back (or the
// collected answer), the outcome and the perf snapshot. Bodies past maxBodyChars are cut and
// flagged. The store is the head's ONLY writer, so "off" is a head built with null, never a
// record with less in it.
package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.storage.DayBodyBudget
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.trace.body.TraceBodies
import splice.upstream.sse.WireAttempt
import splice.upstream.sse.WireRequest
import splice.upstream.sse.WireResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

private val meta = TurnMeta(
    compact = false,
    reasoning = TurnReasoning(
        showReasoning = ReasoningDisplay.TEXT,
        effort = "high",
        summary = "detailed",
        budgetTokens = null,
    ),
    route = TurnRoute(
        stream = true,
        originalModel = "claude-splice--gpt-5.6-sol",
        upstreamModel = "gpt-5.6-sol",
        clientMaxTokens = 8000,
    ),
    scope = TurnScope(sessionId = "sess-abc"),
)

private val inbound = ClientInbound(
    method = "POST",
    path = "/v1/messages",
    headers = mapOf("authorization" to "[redacted]", "content-type" to "application/json"),
    body = """{"model":"claude-splice--gpt-5.6-sol","messages":[]}""",
)

private fun attempt(n: Int, body: String, status: Int? = 200, errorText: String? = null, failure: String? = null) =
    WireAttempt(
        attempt = n,
        request = WireRequest(
            url = "https://up.example/v1",
            headers = mapOf("x-api-key" to "[redacted]", "anthropic-version" to "2023-06-01"),
            body = body,
            encoding = null,
        ),
        response = WireResponse(
            status = status,
            headers = if (status == null) emptyMap() else mapOf("x-request-id" to "r$n"),
            errorText = errorText,
        ),
        failure = failure,
        durationMs = 12,
    )

class TurnTraceTest {

    @TempDir
    lateinit var tmp: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun store(
        maxBodyChars: Int = 1 shl 20,
        heap: HeapReservations = HeapBudget(Long.MAX_VALUE),
        clock: WallClock = WallClock { DAY_ONE },
    ): TraceStore = TraceStore(
        ActivityDays(tmp.resolve("trace"), "kimi", retentionDays = 7, clock = WallClock { DAY_ONE }, ownerOnly = true),
        head = "kimi",
        maxBodyChars = maxBodyChars,
        now = clock,
        ids = TurnIdMint { "turn-0001" },
        heap = heap,
        bodyBudget = DayBodyBudget(minFreeBytes = 0, clock = WallClock { DAY_ONE }),
    )

    private fun lines(): List<JsonObject> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        val file = tmp.resolve("trace/kimi-2026-09-18.jsonl")
        val bodies = TraceBodies()
        return Files.readAllLines(file).map { line ->
            bodies.hydrate(json.parseToJsonElement(line).jsonObject, file)
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    @Test
    fun `a trace refuses buffer growth before mutating its already captured response`() {
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 1024 * 1024)
        val trace = store(heap = heap).begin(meta, inbound)
        trace.responseText("kept")
        val blocked = checkNotNull(heap.reserve(heap.available.value))
        assertThrows(HeapCapacityException::class.java) { trace.responseText("x".repeat(100_000)) }
        blocked.close()
        trace.attempted(attempt(1, "{}"))
        assertEquals("kept", lines().single().at("response", "text"))
    }

    /** The scalar at a nested path, e.g. `at("request", "headers", "x-api-key")`. */
    private fun JsonObject.at(vararg path: String): String? =
        path.dropLast(1).fold(this) { node, key -> node.obj(key) }.str(path.last())

    @Test
    fun `a streamed turn - one attempt with its response text, then the turn record`() {
        val trace = store().begin(meta, inbound)
        trace.responseText("event: message_start\n")
        trace.responseText("data: {}\n\n")
        trace.attempted(attempt(1, """{"model":"gpt-5.6-sol"}"""))
        trace.clientFrame("event: message_start\ndata: {\"a\":1}\n\n")
        trace.clientFrame("event: ping\ndata: {}\n\n")
        trace.finish(
            "ok",
            PerfSnapshot(
                marks = mapOf("total" to 340L),
                counters = mapOf("in_tokens" to 12L, PerfKeys.CODE_MODE_DIVERGENCE to 1L),
            ),
        )

        val (attempt, turn) = lines()

        assertEquals("attempt", attempt.str("kind"))
        assertEquals("turn-0001", attempt.str("turn"))
        assertEquals("kimi", attempt.str("head"))
        assertEquals("sess-abc", attempt.str("session"))
        assertEquals("gpt-5.6-sol", attempt.str("model"))
        assertEquals("1", attempt.str("round"))
        assertEquals("1", attempt.str("attempt"))
        assertEquals("http", attempt.str("transport"))
        assertEquals("https://up.example/v1", attempt.str("url"))
        assertEquals("""{"model":"gpt-5.6-sol"}""", attempt.at("request", "body"))
        assertEquals("[redacted]", attempt.at("request", "headers", "x-api-key"))
        assertEquals("200", attempt.at("response", "status"))
        assertEquals("event: message_start\ndata: {}\n\n", attempt.at("response", "text"))
        assertEquals("r1", attempt.at("response", "headers", "x-request-id"))

        assertEquals("turn", turn.str("kind"))
        assertEquals("turn-0001", turn.str("turn"))
        assertEquals("POST", turn.at("client", "method"))
        assertEquals(inbound.body, turn.at("client", "body"))
        assertEquals("[redacted]", turn.at("client", "headers", "authorization"))
        assertEquals("200", turn.at("answer", "status"))
        assertEquals("true", turn.at("answer", "stream"))
        assertEquals(
            "event: message_start\ndata: {\"a\":1}\n\nevent: ping\ndata: {}\n\n",
            turn.at("answer", "body"),
            "every frame, the pinger's included, in order",
        )
        assertEquals("ok", turn.str("outcome"))
        assertEquals("1", turn.str("rounds"))
        assertEquals("1", turn.str("attempts"))
        assertEquals("340", turn.at("perf", "marks", "total"))
        assertEquals("12", turn.at("perf", "counters", "in_tokens"))
        // Guards the existing pass-through of any perf counter into the trace snapshot.
        assertEquals("1", turn.at("perf", "counters", PerfKeys.CODE_MODE_DIVERGENCE))
    }

    @Test
    fun `retries, a second round and a failed send are each their own attempt, counted on the turn`() {
        val trace = store().begin(meta, inbound)
        trace.attempted(attempt(1, "r1", status = 503, errorText = "busy"))
        trace.responseText("ok-stream")
        trace.attempted(attempt(2, "r1"))
        trace.attempted(attempt(1, "r2", status = null, failure = "IOException: reset"))
        trace.finish("error:upstream", PerfSnapshot(emptyMap(), emptyMap()))

        val records = lines()

        assertEquals(listOf("1", "1", "2"), records.take(3).map { it.str("round") }, "send 1 opens a round")
        assertEquals(listOf("1", "2", "3"), records.take(3).map { it.str("attempt") })
        assertEquals("busy", records[0].at("response", "text"), "a non-2xx keeps its error body")
        assertEquals("ok-stream", records[1].at("response", "text"))
        assertNull(records[2]["response"], "no response came")
        assertEquals("IOException: reset", records[2].str("failure"))
        assertEquals("2", records[3].str("rounds"))
        assertEquals("3", records[3].str("attempts"))
    }

    @Test
    fun `a WebSocket round is an attempt with the events it parsed as its response text`() {
        val trace = store().begin(meta, inbound)
        trace.wsRoundStarted()
        trace.responseText("""{"type":"response.created"}""" + "\n")
        trace.wsAttempt("""{"input":[]}""", mapOf("x-codex-token" to "[redacted]"), failure = null)
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

        val (attempt, turn) = lines()

        assertEquals("ws", attempt.str("transport"))
        assertEquals("""{"input":[]}""", attempt.at("request", "body"))
        assertEquals("""{"input":[]}""".length.toString(), attempt.at("request", "bodyChars"))
        assertEquals(sha256Hex("""{"input":[]}"""), attempt.at("request", "bodySha256"))
        assertEquals("""{"type":"response.created"}""" + "\n", attempt.at("response", "text"))
        assertEquals("1", turn.str("rounds"))
    }

    @Test
    fun `client frames stay recordable while an upstream attempt is being captured`() {
        val capturing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val clock = WallClock {
            if (armed.compareAndSet(true, false)) {
                capturing.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "attempt capture was not released" }
            }
            DAY_ONE
        }
        val trace = store(clock = clock).begin(meta, inbound)
        trace.wsRoundStarted()
        trace.responseText("""{"type":"response.completed"}""")
        val workers = Executors.newFixedThreadPool(2)
        try {
            armed.set(true)
            val attempt = workers.submit { trace.wsAttempt("""{"input":[]}""", emptyMap(), null) }
            try {
                assertTrue(capturing.await(5, TimeUnit.SECONDS), "attempt capture reached its clock")
                // The capture is deliberately held open, not merely made slow by a large fixture.
                workers.submit { trace.clientFrame("data: first\n\ndata: second\n\n") }
                    .get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
            attempt.get(5, TimeUnit.SECONDS)
            trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

            val (record, turn) = lines()
            assertEquals("""{"type":"response.completed"}""", record.at("response", "text"))
            assertEquals("data: first\n\ndata: second\n\n", turn.at("answer", "body"))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `a collect turn's answer is the buffered body and its status, read when the turn closes`() {
        val trace = store().begin(meta, inbound)
        var body = "not yet"
        trace.collectedAnswer { ClientAnswer(200, body) }
        body = """{"type":"message","content":[]}"""
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

        val turn = lines().single()

        assertEquals("false", turn.at("answer", "stream"))
        assertEquals("""{"type":"message","content":[]}""", turn.at("answer", "body"))
    }

    @Test
    fun `bodies past maxBodyChars are cut and say so - request, response text and client body alike`() {
        val trace = store(maxBodyChars = 5).begin(meta, inbound)
        trace.responseText("0123")
        trace.responseText("456789")
        trace.attempted(attempt(1, "abcdefgh"))
        trace.clientFrame("frame-one\n")
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

        val (attempt, turn) = lines()

        assertEquals("abcde", attempt.at("request", "body"))
        assertEquals("true", attempt.at("request", "truncated"))
        assertEquals("01234", attempt.at("response", "text"))
        assertEquals("true", attempt.at("response", "truncated"))
        assertEquals(inbound.body.take(5), turn.at("client", "body"))
        assertEquals("true", turn.at("client", "truncated"))
        assertEquals("frame", turn.at("answer", "body"))
        assertEquals("true", turn.at("answer", "truncated"))
    }

    @Test
    fun `two sends cut to the same text still differ - each record keeps its whole body's length and SHA-256`() {
        val trace = store(maxBodyChars = 5).begin(meta, inbound)
        trace.attempted(attempt(1, "abcde-first-send"))
        trace.attempted(attempt(2, "abcde-second-send!"))
        trace.attempted(attempt(3, "abc"))
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

        val (first, second, short) = lines()

        assertEquals("abcde", first.at("request", "body"))
        assertEquals("abcde", second.at("request", "body"), "the cut tail is the only difference")
        assertEquals("16", first.at("request", "bodyChars"))
        assertEquals("18", second.at("request", "bodyChars"))
        assertEquals(sha256Hex("abcde-first-send"), first.at("request", "bodySha256"))
        assertEquals(sha256Hex("abcde-second-send!"), second.at("request", "bodySha256"))
        assertEquals("3", short.at("request", "bodyChars"), "a body kept whole carries them too")
        assertEquals(sha256Hex("abc"), short.at("request", "bodySha256"))
    }

    @Test
    fun `the digest is of the body's UTF-8 bytes, whole, whatever its characters and size`() {
        // 30,000 UTF-8 bytes of 2-, 4- and 3-byte characters, shifted by one to three characters so that a surrogate
        // pair falls on every alignment a fixed-size piece of the body could end at.
        val bodies = (0..3).map { shift -> "x".repeat(shift) + "é😀世".repeat(3_000) }
        val trace = store().begin(meta, inbound)
        bodies.forEachIndexed { n, body -> trace.attempted(attempt(n + 1, body)) }
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))

        val records = lines().take(bodies.size)

        assertEquals(bodies.map { it.length.toString() }, records.map { it.at("request", "bodyChars") })
        assertEquals(bodies.map { sha256Hex(it) }, records.map { it.at("request", "bodySha256") })
        assertEquals(bodies, records.map { it.at("request", "body") })
    }

    private fun sha256Hex(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
