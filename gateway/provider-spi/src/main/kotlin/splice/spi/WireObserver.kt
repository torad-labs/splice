// NEW: V4-174 — what ONE upstream HTTP attempt looked like on the wire, handed to whoever asked
// (a head's trace) after the attempt ends, whichever way it ends. This is the only place retries,
// the single-flight 401 refresh, the G5 stream reissue and the RC-4 body amendment are all
// visible as separate sends: a record taken at the round would show the round's first draft and
// call it what was sent, when the amended resend or the third backoff attempt is what the
// upstream actually saw. So the observer sits INSIDE the retry loop (UpstreamClient.runAttempt),
// one call per send, numbered in the order they left.
//
// HEADERS ARE REDACTED BEFORE THEY CROSS THIS SEAM — the assembly (UpstreamRequest.prepare) is
// the one place that knows every header that went out, credential included, and it hands the
// recorder the redacted map, so a consumer of this port never holds a bearer, an API key or a
// cookie and cannot log one by mistake (constitution IV.5). Bodies are EXACT: they are the point.
package splice.spi

import splice.core.util.ElapsedClock

/** One send, as it left and as it came back. [attempt] counts sends within one [UpstreamClient.post]
 *  (a round), from 1. Exactly one of the three endings is present: a [status] (with
 *  [responseHeaders], and [errorText] when it was not 2xx — the 2xx body is the stream the caller
 *  consumed), or a [failure] (the transport threw before or during the response: class and message,
 *  and [status] is null). [requestEncoding] names a content-encoding the body rode under, when any;
 *  [requestBody] is the JSON before that encoding. */
public data class WireAttempt(
    val attempt: Int,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val requestEncoding: String?,
    val status: Int?,
    val responseHeaders: Map<String, String>,
    val errorText: String?,
    val failure: String?,
    val durationMs: Long,
)

/** Hears every send of a post, after it ends. Null on a [PostContext] means nothing is recorded and
 *  nothing is allocated — the hot path for every head that did not opt in. */
public fun interface WireObserver {
    public fun attempted(attempt: WireAttempt)
}

/** The mutable half of one attempt while it is in flight: the assembly fills the request side,
 *  the execute block fills the response side, and the loop closes it with the ending it saw.
 *  Built ONLY when the context carries an observer. */
internal class AttemptRecorder(
    private val attempt: Int,
    private val url: String,
    private val requestBody: String,
    private val requestEncoding: String?,
    private val clock: ElapsedClock,
) {
    private val startedAt = clock()
    private var requestHeaders: Map<String, String> = emptyMap()
    private var status: Int? = null
    private var responseHeaders: Map<String, String> = emptyMap()
    private var errorText: String? = null

    /** The headers as assembled for the wire; stored redacted, never raw. */
    fun request(headers: Map<String, String>) {
        requestHeaders = HeaderRedaction.redact(headers)
    }

    fun response(status: Int, headers: Map<String, String>) {
        this.status = status
        responseHeaders = HeaderRedaction.redact(headers)
    }

    fun errorText(text: String) {
        errorText = text
    }

    /** [failure] is the transport throwable that ended the attempt, when one did. */
    fun finish(failure: Throwable?): WireAttempt = WireAttempt(
        attempt = attempt,
        url = url,
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        requestEncoding = requestEncoding,
        status = status,
        responseHeaders = responseHeaders,
        errorText = errorText,
        failure = failure?.let { it::class.simpleName + (it.message?.let { m -> ": $m" } ?: "") },
        durationMs = clock() - startedAt,
    )
}
