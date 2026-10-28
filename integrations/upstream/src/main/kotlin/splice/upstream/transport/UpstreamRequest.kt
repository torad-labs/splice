// PORT-OF: splice/spi/UpstreamClient.kt (RequestBody, HeaderRules, applyAuth, attemptRequest's assembly half) @ 3879c4c — invariants unchanged: bodies encoded once and NEVER gzipped, case-insensitive header dedupe, forward mode writes no auth header.
//
// ASSEMBLY of one upstream POST (HD-25): the prepared statement here; the body bytes and the headers in
// UpstreamRequestWire.kt.
// Was UpstreamClient.RequestBody / HeaderRules / applyAuth and the first half of attemptRequest;
// only the receiver moved.
//
// THE SEAM IS `statement.execute`. Status, error body and Retry-After are extracted INSIDE that
// block because the response body channel dies at its close — which is why the whole execute
// moved here rather than being split (HD-25 follow-up, 2026-08-20). The loop still owns the
// four budgets; this file owns one attempt's HTTP round-trip.
//
// Request bodies are NEVER gzipped: xAI 400s on a gzipped body ("Failed to parse the request body
// as JSON: expected value at line 1 column 1" — verified live 2026-07-18, first >=2KiB turn after
// the gzip experiment deployed); ChatGPT is unproven. The body still rides as pre-encoded UTF-8
// bytes so retries never re-encode the string.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import splice.core.failure.FailureEvidence
import splice.core.perf.UpstreamAttemptTiming
import splice.core.util.WallClock
import splice.upstream.RoundBody
import splice.upstream.StreamStart
import splice.upstream.UpstreamHandler
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ForeignCredential
import splice.upstream.failure.ReplyFacts
import java.time.Instant

internal class UpstreamRequest(
    private val client: HttpClient,
    private val zstdRequestBody: Boolean,
    /** V4-233: judges whether a plan-limit reset is still ahead; injectable so a test can pin it. */
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** Encode ONCE; retries resend the same bytes (no per-attempt string re-encode). Never gzip. */
    fun body(body: RoundBody): RequestBody = RequestBody(body, zstdRequestBody)

    /** The prepared POST, up to but NOT including `execute` — [execute] owns the block because
     *  the response body channel only lives inside it. */
    suspend fun prepare(
        url: String,
        wire: AttemptWire,
        timingToken: String? = null,
        bodyToken: String? = null,
    ): HttpStatement {
        val allHeaders = wire.headers()
        return client.preparePost(url) {
            contentType(ContentType.Application.Json)
            headers {
                allHeaders.forEach { (k, v) -> append(k, v) }
                if (zstdRequestBody) append("Content-Encoding", "zstd")
                if (timingToken != null) {
                    remove(UPSTREAM_TIMING_HEADER)
                    append(UPSTREAM_TIMING_HEADER, timingToken)
                }
                if (bodyToken != null) {
                    remove(UPSTREAM_BODY_HEADER)
                    append(UPSTREAM_BODY_HEADER, bodyToken)
                }
            }
            setBody(wire.content())
        }
    }

    /** The READ-BEFORE-CLOSE half of one attempt. Status, error body and Retry-After are all
     *  extracted INSIDE the execute block because the response body channel dies at its close. */
    suspend fun <T> execute(
        ctx: PostContext,
        wire: AttemptWire,
        auth: AttemptCredentials,
        onStreamStart: StreamStart,
        block: UpstreamHandler<T>,
    ): RetryOutcome<T> {
        val postedAtMs = auth.postedAtMs
        val timing = ctx.observers.perf?.let(::UpstreamAttemptTiming)
        val route = ctx.recovery.route
        // The vendor's route hears the write and the headers the timing hears, perf observed or not.
        val marks = if (timing == null && route == null) null else AttemptMarks(timing, route)
        val bridge = client.attributes.getOrNull(upstreamTimingBridgeKey)
        val token = marks?.let { bridge?.register(it) }
        val bodies = client.attributes.getOrNull(upstreamBodyBridgeKey)
        val bodyToken = bodies?.register(wire.bodyBytes)
        try {
            val statement = prepare(ctx.url, wire, token, bodyToken)
            val handler = UpstreamHandler<T> { response ->
                block(response.also { it.postedAtMs = postedAtMs })
            }
            val acceptance = auth.cooldown.acceptance()
            val accepted = StreamStart {
                acceptance()
                onStreamStart()
            }
            return statement.execute { response ->
                timing?.headersDelivered()
                executeResponse(response, ctx, accepted, handler, wire)
            }
        } finally {
            token?.let { bridge?.release(it) }
            bodyToken?.let { bodies.release(it) }
        }
    }

    private suspend fun <T> executeResponse(
        resp: HttpResponse,
        ctx: PostContext,
        onStreamStart: StreamStart,
        block: UpstreamHandler<T>,
        wire: AttemptWire,
    ): RetryOutcome<T> {
        val recorder = wire.recorder
        ctx.markHeaders()
        val answeredAt = wallClock()
        ctx.providerAnswerObserver.observed(resp.status.value, answeredAt)
        // V4-220 item 6b: every answer reaches the provider, so a forwarded credential's verdict is known.
        ctx.auth.upstreamAnswered(resp.status.value, resp.status.isSuccess())
        recorder?.response(resp.status.value, resp.headers.entries().associate { (k, v) -> k to v.joinToString() })
        return if (resp.status.isSuccess()) {
            onStreamStart()
            ctx.upstreamAccepted()
            RetryOutcome.Done(block(UpstreamResponse(resp)))
        } else {
            val text = UpstreamResponse(resp).bodyTextLimited(MAX_ERROR_BODY_BYTES)
            recorder?.errorText(text)
            // Failure-handling slice 3: ONE reply, read ONCE, where its status, headers and body are all in hand.
            val reply = FailedReply(
                status = resp.status.value,
                headers = resp.headers.entries().associate { (name, values) -> name to values.joinToString() },
                body = text,
                owner = ctx.auth.owner,
                sent = ForeignCredential.sentKey(wire.creds),
                answeredAt = Instant.ofEpochMilli(answeredAt),
                // The Responses WebSocket peer already refused these exact bytes as too big (RFC 6455 1009), so a
                // 4xx for them is the second refusal of the same body and its reader reads an overflow.
                bodyRefusedAsTooLarge = ctx.bodyRefusedAsTooLarge,
            )
            // The vendor hears every answer that is not a stream; whether its route survives the answer is its call.
            ctx.recovery.route?.answered(reply)

            val failed = failure(reply, ctx, wire)
            ctx.observeQuotaRefusal(failed, answeredAt)
            failed
        }
    }

    /** One failed answer, read ONCE by the head's own reader while its response still lives: the cause it is, and
     *  the evidence it leaves (its status, its body as it came, the pushback its Retry-After asked for, and the
     *  provider's own words as V4-444 judges them for the console's Requests list, with the sent key masked).
     *
     *  The plan window and the dead-credential answer come from the same reader and the same reply, so no layer
     *  above reads a status or a body again (failure-handling slice 3, final-design.md section 7). */
    private fun failure(
        reply: FailedReply,
        ctx: PostContext,
        wire: AttemptWire,
    ): RetryOutcome.Failed {
        val cause = ctx.replies.http(reply)
        return RetryOutcome.Failed(
            cause = cause,
            evidence = FailureEvidence(
                // The provider's own message for the journal, as today's evidence kept it; its body rides whole below.
                message = ErrorMessage.of(reply.body) ?: reply.body,
                status = reply.status,
                // The perf row's provider_message (V4-444): the provider's own sentence whole, with the credential this
                // attempt sent masked out. A line a person reads quotes the cause's filtered words instead.
                said = ForeignCredential.upstreamsWords(ErrorMessage.of(reply.body), wire.creds),
                body = reply.body,
                // Read against the instant the answer came, as the reply's reader reads it.
                retryAfterMs = ReplyFacts.retryAfterMs(reply),
            ),
            planLimit = ctx.replies.planLimit(reply),
            deadCredential = ctx.replies.deadCredential(reply),
        )
    }
}

private const val MAX_ERROR_BODY_BYTES = 64 * 1024
