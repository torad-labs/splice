// NEW: V4-174 — the records one turn writes, read back from the day file: an attempt per send
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
import splice.core.activity.ActivityDays
import splice.core.perf.PerfSnapshot
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.upstream.sse.WireAttempt
import java.nio.file.Files
import java.nio.file.Path

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

private val meta = TurnMeta(
    compact = false,
    showReasoning = ReasoningDisplay.TEXT,
    stream = true,
    originalModel = "claude-splice--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    clientMaxTokens = 8000,
    effort = "high",
    summary = "detailed",
    budgetTokens = null,
    sessionId = "sess-abc",
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
        url = "https://up.example/v1",
        requestHeaders = mapOf("x-api-key" to "[redacted]", "anthropic-version" to "2023-06-01"),
        requestBody = body,
        requestEncoding = null,
        status = status,
        responseHeaders = if (status == null) emptyMap() else mapOf("x-request-id" to "r$n"),
        errorText = errorText,
        failure = failure,
        durationMs = 12,
    )

class TurnTraceTest {

    @TempDir
    lateinit var tmp: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun store(maxBodyChars: Int = 1 shl 20): TraceStore = TraceStore(
        ActivityDays(tmp.resolve("trace"), "kimi", retentionDays = 7, clock = WallClock { DAY_ONE }, ownerOnly = true),
        head = "kimi",
        maxBodyChars = maxBodyChars,
        now = WallClock { DAY_ONE },
        ids = TurnIdMint { "turn-0001" },
    )

    private fun lines(): List<JsonObject> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        return Files.readAllLines(tmp.resolve("trace/kimi-2026-09-18.jsonl")).map { line ->
            json.parseToJsonElement(line).jsonObject
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

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
        trace.finish("ok", PerfSnapshot(marks = mapOf("total" to 340L), counters = mapOf("in_tokens" to 12L)))

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
        assertEquals("""{"type":"response.created"}""" + "\n", attempt.at("response", "text"))
        assertEquals("1", turn.str("rounds"))
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
    fun `a store that keeps no body cannot be built - off is null, never a smaller record`() {
        assertThrows(IllegalArgumentException::class.java) { store(maxBodyChars = 0) }
    }
}
