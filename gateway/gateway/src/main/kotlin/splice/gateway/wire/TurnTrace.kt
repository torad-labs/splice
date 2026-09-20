// NEW: V4-174 — one turn's trace, from the request as it arrived to the perf row that closed it.
// The seams feed it from four places and it writes two kinds of record:
//   · attempt — one per upstream SEND. The SSE path reports through [WireObserver] from inside
//     UpstreamClient's retry loop (so a backoff retry, the refresh's free retry, a G5 reissue and
//     the RC-4 amended resend are each their own record, numbered as they left); the raw response
//     text of a 2xx is what TearAwareEvents hands [responseText] while the stream is consumed, and
//     the record closes with it. The WebSocket path reports one record per round through
//     [wsAttempt], with the events the runner parsed as its response text.
//   · turn — once, when the perf row is written (TurnTelemetry.recordPerf, the one site every
//     ending goes through): the client's inbound request, everything streamed back to it (every
//     frame through ClientChannel, the pinger's included) or the collected JSON body, the outcome
//     tag, the round and attempt counts, and the perf marks and counters.
// Bodies past the store's maxBodyChars are cut and the record says `truncated: true`.
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.perf.PerfSnapshot
import splice.core.turn.TurnMeta
import splice.core.util.WallClock
import splice.spi.WireAttempt
import splice.spi.WireObserver

/** What a collect turn answered: the collect path registers it before driving, and the turn record
 *  reads it at the end, because the buffered JSON body exists only once the terminal has closed. */
public data class ClientAnswer(val status: Int, val body: String)

public fun interface ClientAnswerSource {
    public fun answer(): ClientAnswer
}

public class TurnTrace internal constructor(
    private val store: TraceStore,
    public val turnId: String,
    private val meta: TurnMeta,
    private val inbound: ClientInbound,
    private val now: WallClock,
) : WireObserver {
    private val lock = Any()
    private val response = BoundedText(store.maxBodyChars)
    private val streamed = BoundedText(store.maxBodyChars)
    private var collected: ClientAnswerSource? = null
    private var rounds = 0
    private var attempts = 0
    private var wsStartedAt = 0L

    /** The upstream's raw response text as it is consumed, for the attempt in flight. */
    public fun responseText(text: CharSequence) {
        synchronized(lock) { response.append(text) }
    }

    /** One SSE send ended (headers redacted upstream of this seam, body exact). Takes the response
     *  text gathered since the previous attempt closed. */
    override fun attempted(attempt: WireAttempt) {
        val record = synchronized(lock) {
            if (attempt.attempt == 1) rounds += 1
            attempts += 1
            val (text, truncated) = response.take()
            attemptRecord(attempt, text, truncated)
        }
        store.write(record)
    }

    /** A WebSocket round begins: the request as handed to the runner. */
    public fun wsRoundStarted() {
        synchronized(lock) {
            rounds += 1
            attempts += 1
            wsStartedAt = now()
        }
    }

    /** The WebSocket round ended; [failure] names the transport failure when one ended it. Its
     *  response text is the events the runner parsed, one JSON object per line. */
    public fun wsAttempt(body: String, headers: Map<String, String>, failure: String?) {
        val record = synchronized(lock) {
            val (text, truncated) = response.take()
            val (requestBody, requestTruncated) = store.bounded(body)
            buildJsonObject {
                stamp(this, TraceKinds.ATTEMPT)
                put("round", rounds)
                put("attempt", attempts)
                put("transport", "ws")
                putJsonObject(REQUEST) {
                    headersOf(this, headers)
                    put(BODY, requestBody)
                    put(TRUNCATED, requestTruncated)
                }
                putJsonObject(RESPONSE) {
                    put(RAW_TEXT, text)
                    put(TRUNCATED, truncated)
                }
                failure?.let { put("failure", it) }
                put("durationMs", now() - wsStartedAt)
            }
        }
        store.write(record)
    }

    /** Every frame the head wrote toward the client, in order — the mirror of FrameRecording. */
    public fun clientFrame(frame: String) {
        synchronized(lock) { streamed.append(frame) }
    }

    /** The collect path's answer, read when the turn record is written. */
    public fun collectedAnswer(source: ClientAnswerSource) {
        synchronized(lock) { collected = source }
    }

    /** The turn ended with [outcomeTag]; [perf] is the row's snapshot. Writes the turn record. */
    public fun finish(outcomeTag: String, perf: PerfSnapshot) {
        val record = synchronized(lock) { turnRecord(outcomeTag, perf) }
        store.write(record)
    }

    private fun attemptRecord(attempt: WireAttempt, responseText: String, responseTruncated: Boolean): JsonObject {
        val (requestBody, requestTruncated) = store.bounded(attempt.requestBody)
        return buildJsonObject {
            stamp(this, TraceKinds.ATTEMPT)
            put("round", rounds)
            put("attempt", attempts)
            put("transport", "http")
            put("send", attempt.attempt)
            put("url", attempt.url)
            putJsonObject(REQUEST) {
                headersOf(this, attempt.requestHeaders)
                attempt.requestEncoding?.let { put("encoding", it) }
                put(BODY, requestBody)
                put(TRUNCATED, requestTruncated)
            }
            attempt.status?.let { status ->
                putJsonObject(RESPONSE) {
                    put("status", status)
                    headersOf(this, attempt.responseHeaders)
                    put(RAW_TEXT, attempt.errorText ?: responseText)
                    put(TRUNCATED, attempt.errorText == null && responseTruncated)
                }
            }
            attempt.failure?.let { put("failure", it) }
            put("durationMs", attempt.durationMs)
        }
    }

    private fun turnRecord(outcomeTag: String, perf: PerfSnapshot): JsonObject {
        val (clientBody, clientTruncated) = store.bounded(inbound.body)
        val answer = collected?.answer()
        val (answerBody, answerTruncated) = answer?.let { store.bounded(it.body) } ?: streamed.take()
        return buildJsonObject {
            stamp(this, TraceKinds.TURN)
            putJsonObject("client") {
                put("method", inbound.method)
                put("path", inbound.path)
                headersOf(this, inbound.headers)
                put(BODY, clientBody)
                put(TRUNCATED, clientTruncated)
            }
            putJsonObject("answer") {
                put("status", answer?.status ?: OK_STATUS)
                put("stream", answer == null)
                put(BODY, answerBody)
                put(TRUNCATED, answerTruncated)
            }
            put("outcome", outcomeTag)
            put("rounds", rounds)
            put("attempts", attempts)
            putJsonObject("perf") {
                putJsonObject("marks") { perf.marks.forEach { (k, v) -> put(k, v) } }
                putJsonObject("counters") { perf.counters.forEach { (k, v) -> put(k, v) } }
            }
        }
    }

    private fun stamp(into: JsonObjectBuilder, kind: String) {
        store.stamp(kind, turnId, meta).forEach { (k, v) -> into.put(k, v) }
    }

    private fun headersOf(into: JsonObjectBuilder, headers: Map<String, String>) {
        into.putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } }
    }
}

/** A text buffer that stops growing at [max] and remembers that it did. */
private class BoundedText(private val max: Int) {
    private val text = StringBuilder()
    private var truncated = false

    fun append(chunk: CharSequence) {
        val room = max - text.length
        if (chunk.length <= room) {
            text.append(chunk)
        } else {
            if (room > 0) text.append(chunk, 0, room)
            truncated = true
        }
    }

    /** The text so far and the flag; the buffer is empty afterwards. */
    fun take(): Pair<String, Boolean> {
        val taken = text.toString() to truncated
        text.setLength(0)
        truncated = false
        return taken
    }
}

private const val REQUEST = "request"
private const val RESPONSE = "response"
private const val BODY = "body"
private const val RAW_TEXT = "text"
private const val TRUNCATED = "truncated"
