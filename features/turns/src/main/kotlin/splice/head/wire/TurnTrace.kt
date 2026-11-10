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
// Bodies past the store's maxBodyChars are cut and the record says `truncated: true`. An attempt's request also carries
// `bodyChars` and `bodySha256`, the whole body's length and the SHA-256 of its UTF-8 bytes taken before any cut, so
// two sends that differ only past the cut still differ in the record (Nov 8: three 20-30 MB bodies read identical).
// A header a body or a stream carries as a JSON member keeps its name and loses a secret value
// (HeaderRedaction.redactText), as the headers themselves do: the ChatGPT backend's response.metadata event names its
// turn state, and a websocket frame echoes it.
// The text that ended an attempt goes the same way, because a provider's refusal quotes the headers it refused.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.failure.SpliceLine
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.perf.PerfSnapshot
import splice.core.turn.TurnMeta
import splice.core.util.ERR_SNIPPET
import splice.core.util.WallClock
import splice.upstream.sse.WireAttempt
import splice.upstream.sse.WireObserver
import splice.upstream.transport.HeaderRedaction
import java.security.MessageDigest
import java.util.HexFormat

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
    inbound: ClientInbound,
    private val now: WallClock,
) : WireObserver {
    private val lock = Any()
    private var inbound: ClientInbound? = inbound.also {
        HeapOwners.charge(
            it,
            store.heap,
            HeapJson.text(it.body) + it.headers.entries.sumOf { header ->
                HeapJson.text(header.key) + HeapJson.text(header.value)
            },
        )
    }
    private val response = BoundedText(store.maxBodyChars, store.heap)
    private val streamed = BoundedText(store.maxBodyChars, store.heap)
    private var collected: ClientAnswerSource? = null
    private var failureSentenceText: String? = null
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
            buildJsonObject {
                stamp(this, TraceKinds.ATTEMPT)
                put("round", rounds)
                put("attempt", attempts)
                put("transport", "ws")
                putJsonObject(REQUEST) {
                    headersOf(this, headers)
                    requestBodyOf(this, HeaderRedaction.redactText(body))
                }
                putJsonObject(RESPONSE) {
                    put(RAW_TEXT, HeaderRedaction.redactText(text))
                    put(TRUNCATED, truncated)
                }
                failure?.let { put("failure", HeaderRedaction.redactText(it)) }
                put("durationMs", now() - wsStartedAt)
            }
        }
        store.write(record)
    }

    /** Every frame the head wrote toward the client, in order — the mirror of FrameRecording. */
    public fun clientFrame(frame: String) {
        // Attempt redaction must not hold up client delivery; only the answer buffer is shared here.
        synchronized(streamed) { streamed.append(frame) }
    }

    /** The collect path's answer, read when the turn record is written. */
    public fun collectedAnswer(source: ClientAnswerSource) {
        synchronized(lock) { collected = source }
    }

    /** Human transport failure text from the ending that logged it, not the raw attempt exception.
     *  The provider URL path and query were stripped by TransportFailureReason before this call. */
    public fun failureSentence(sentence: String) {
        synchronized(lock) { failureSentenceText = sentence.take(ERR_SNIPPET) }
    }

    /** The line this turn ended with, recorded whole as its sentence without the prefix, so the record says what the
     *  client read in the same words (failure-handling slice 2). A line is bounded where it is made. */
    public fun failureLine(line: SpliceLine) {
        synchronized(lock) { failureSentenceText = line.sentence }
    }

    /** The turn ended with [outcomeTag]; [perf] is the row's snapshot. Writes the turn record. */
    public fun finish(outcomeTag: String, perf: PerfSnapshot) {
        val record = synchronized(lock) {
            val client = inbound ?: return
            turnRecord(client, outcomeTag, perf).also { inbound = null }
        }
        store.write(record)
    }

    private fun attemptRecord(attempt: WireAttempt, responseText: String, responseTruncated: Boolean): JsonObject {
        return buildJsonObject {
            stamp(this, TraceKinds.ATTEMPT)
            put("round", rounds)
            put("attempt", attempts)
            put("transport", "http")
            put("send", attempt.attempt)
            put("url", attempt.request.url)
            putJsonObject(REQUEST) {
                headersOf(this, attempt.request.headers)
                attempt.request.encoding?.let { put("encoding", it) }
                requestBodyOf(this, attempt.request.body)
            }
            attempt.response.status?.let { status ->
                putJsonObject(RESPONSE) {
                    put("status", status)
                    headersOf(this, attempt.response.headers)
                    put(RAW_TEXT, HeaderRedaction.redactText(attempt.response.errorText ?: responseText))
                    put(TRUNCATED, attempt.response.errorText == null && responseTruncated)
                }
            }
            attempt.failure?.let { put("failure", HeaderRedaction.redactText(it)) }
            put("durationMs", attempt.durationMs)
        }
    }

    private fun turnRecord(inbound: ClientInbound, outcomeTag: String, perf: PerfSnapshot): JsonObject {
        val (clientBody, clientTruncated) = store.bounded(inbound.body)
        val answer = collected?.answer()
        val (answerBody, answerTruncated) = answer?.let { store.bounded(it.body) }
            ?: synchronized(streamed) { streamed.take() }
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
            failureSentenceText?.let { put("failure_sentence", it) }
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

    /** An attempt's request body as the record keeps it: the text cut to the store's limit and whether it was cut, and
     *  the whole body's length and SHA-256, taken from [body] itself before the cut. The cut tail is gone from the
     *  record, so these are what still tells two sends apart whose bodies differ only past it. */
    private fun requestBodyOf(into: JsonObjectBuilder, body: String) {
        val (kept, truncated) = store.bounded(body)
        into.put(BODY, kept)
        into.put(TRUNCATED, truncated)
        into.put(BODY_CHARS, body.length)
        into.put(BODY_SHA256, Utf8Digest.sha256(body))
    }
}

/** A text buffer that stops growing at [max] and remembers that it did. */
private class BoundedText(private val max: Int, private val heap: HeapReservations) {
    private val text = StringBuilder()
    private val heapLease = HeapOwners.charge(text, heap, HeapJson.text(text) + text.capacity() * 2L)
    private var truncated = false

    fun append(chunk: CharSequence) {
        val needed = text.length.toLong() + minOf(chunk.length, max - text.length)
        val capacity = if (needed <= text.capacity()) {
            text.capacity().toLong()
        } else {
            maxOf(needed, text.capacity() * 2L + 2L)
        }
        if (!heapLease.resize(capacity * 2L + HeapJson.text(""))) throw HeapCapacityException()
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
        val copy = heap.reserve(HeapJson.text(text)) ?: throw HeapCapacityException()
        var kept = false
        try {
            val value = text.toString()
            HeapOwners.keep(value, copy)
            kept = true
            val taken = value to truncated
            // Length is reset, not capacity. The builder's high-water reservation remains charged.
            text.setLength(0)
            truncated = false
            return taken
        } finally {
            if (!kept) copy.close()
        }
    }
}

/** The SHA-256 of a text's UTF-8 bytes: what `sha256sum` prints for the bytes a body went out as. The text goes into
 *  the digest one small piece at a time, so a body of tens of megabytes is never copied whole to be hashed. */
private object Utf8Digest {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var start = 0
        while (start < text.length) {
            var end = minOf(start + DIGEST_PIECE_CHARS, text.length)
            // A surrogate pair split between two pieces would encode as two replacement bytes, not its four.
            if (end < text.length && text[end - 1].isHighSurrogate()) end += 1
            digest.update(text.substring(start, end).toByteArray(Charsets.UTF_8))
            start = end
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

// why: measured on a 30 MB body, 8 Ki-character pieces hash it within milliseconds of one pass over a whole copy,
// while holding a few tens of KiB of it at a time.
private const val DIGEST_PIECE_CHARS = 8192

private const val REQUEST = "request"
private const val RESPONSE = "response"
private const val BODY = "body"
private const val RAW_TEXT = "text"
private const val TRUNCATED = "truncated"
private const val BODY_CHARS = "bodyChars"
private const val BODY_SHA256 = "bodySha256"
