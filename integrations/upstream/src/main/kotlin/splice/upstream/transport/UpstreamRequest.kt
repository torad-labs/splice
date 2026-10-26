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
import splice.core.auth.Credentials
import splice.core.failure.Lift
import splice.core.perf.UpstreamAttemptTiming
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.core.wire.NativeReply
import splice.upstream.RoundBody
import splice.upstream.StreamStart
import splice.upstream.UpstreamHandler
import splice.upstream.failure.FailureRules
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier
import splice.upstream.failure.VendorRefusals
import splice.upstream.failure.WsSizeRefusal
import splice.upstream.retry.MS_PER_S
import splice.upstream.retry.ProviderReset
import splice.upstream.retry.RetryAfter
import splice.upstream.sse.AttemptRecorder

internal class UpstreamRequest(
    private val client: HttpClient,
    private val zstdRequestBody: Boolean,
    /** V4-233: judges whether a plan-limit reset is still ahead; injectable so a test can pin it. */
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val retryAfter = RetryAfter()
    private val failureRules = FailureRules()

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
                sentWith(executeResponse(response, ctx, accepted, handler, wire.recorder), auth.credentials)
            }
        } finally {
            token?.let { bridge?.release(it) }
            bodyToken?.let { bodies.release(it) }
        }
    }

    /** [outcome], remembering on a failure the credential the attempt sent (ForeignCredential reads it at the
     *  loop's exit). */
    private fun <T> sentWith(outcome: RetryOutcome<T>, sent: Credentials): RetryOutcome<T> =
        if (outcome is RetryOutcome.Failed) outcome.copy(sent = sent) else outcome

    private suspend fun <T> executeResponse(
        resp: HttpResponse,
        ctx: PostContext,
        onStreamStart: StreamStart,
        block: UpstreamHandler<T>,
        recorder: AttemptRecorder?,
    ): RetryOutcome<T> {
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
            val realStatus = resp.status.value
            val text = UpstreamResponse(resp).bodyTextLimited(MAX_ERROR_BODY_BYTES)
            recorder?.errorText(text)
            // The vendor hears every answer that is not a stream; whether its route survives the answer is its call.
            ctx.recovery.route?.answered(realStatus, text)
            val failed = failure(resp, ctx, text, answeredAt)
            ctx.observeQuotaRefusal(realStatus, answeredAt, failed.planLimit)
            WsSizeRefusal.read(ctx, failed)
        }
    }

    /** One failed answer, read while its response still lives: its status as every retry layer sees it, its [text],
     *  its pushback, the plan window a 429 names, the provider's own 429 for a client that reads it natively, and what
     *  the answer says beyond its status ([VendorReading]). */
    private fun failure(resp: HttpResponse, ctx: PostContext, text: String, answeredAt: Long): RetryOutcome.Failed {
        val realStatus = resp.status.value
        // A refusal the vendor knows keeps its own plan; any other status keeps V4-73's quota reading.
        val vendor = ctx.auth as? VendorRefusals
        val refusal = vendor?.refusal(realStatus, text)
        val payment = refusal == null && quotaExhausted(realStatus, text, ctx)
        val status = refusal?.status ?: if (payment) HttpStatus.TOO_MANY_REQUESTS else realStatus
        // V4-233: the plan window a 429 names, read here because the headers die with the response like the body does.
        val nowSeconds = wallClock() / MS_PER_S
        val limited = status == HttpStatus.TOO_MANY_REQUESTS
        val headerPlan = if (limited) ctx.auth.planLimit({ name -> resp.headers[name] }, nowSeconds) else null
        val plan = headerPlan ?: if (limited) ctx.auth.planLimitFromBody(text, nowSeconds) else null
        return RetryOutcome.Failed(
            status,
            text,
            retryAfter.retryAfterMs(resp.headers["Retry-After"]),
            planLimit = plan,
            native = nativeReply(resp, ctx, text),
            vendor = VendorReading(
                refusal,
                deadCredential = vendor?.isAuthFailure(status, text) == true,
                said = UpstreamFailureClassifier.saidIn(FailureSource.HTTP, text),
                payment = payment,
                lift = if (limited) ProviderReset.lift(plan, headerPlan != null, text, answeredAt) else Lift.Unstated,
            ),
        )
    }

    /** The provider's own 429 with the headers a native client reads, kept for the passthrough dialect only. */
    private fun nativeReply(resp: HttpResponse, ctx: PostContext, text: String): NativeReply? {
        if (!ctx.relayRateLimitReplies || resp.status.value != HttpStatus.TOO_MANY_REQUESTS) return null
        return NativeReply(
            text,
            resp.headers.entries().filter { (name, _) ->
                name.equals("retry-after", ignoreCase = true) ||
                    name.equals("x-should-retry", ignoreCase = true) ||
                    name.startsWith("anthropic-ratelimit-", ignoreCase = true)
            }.associate { (name, values) -> name to values.toList() },
        )
    }

    /**
     * V4-73: A QUOTA EXHAUSTION IS A RATE LIMIT, whatever status the vendor dressed it in.
     *
     *  One rewrite, at the single site that builds [RetryOutcome.Failed], is what makes every layer
     *  above agree without any of them learning a vendor's spelling: rateLimitedPlan arms the shared
     *  cooldown, the account pool marks the account unavailable and moves on, the thrown
     *  UpstreamFailed carries 429, the classifier's RATE_LIMIT-by-status fires, and the admission
     *  refusal answers with the headers the client's persistent retry reads.
     *
     *  AUTH REFRESH IS NEVER SPENT ON A REWRITTEN FAILURE, structurally rather than by a second
     *  guard: the refresh decision asks isAuthRefreshableFailure, which is true only for 401 and for a
     *  status the vendor named a dead credential, asked of the status after this rewrite: 429. An
     *  UNRECOGNISED 403 keeps its status and therefore keeps V4-38's freshness rule exactly.
     *
     *  The body rides through UNCHANGED, and the answer is read for what it is besides: a payment the
     *  provider asks for (VendorReading.payment), which the turn's ending names as NeedsPayment, so a
     *  person reads the credit line rather than a rate limit (failure-handling slice 2).
     */
    private fun quotaExhausted(realStatus: Int, body: String, ctx: PostContext): Boolean {
        val exhausted = failureRules.isQuotaExhaustionStatus(realStatus) ||
            ctx.auth.isQuotaExhausted(realStatus, body)
        if (exhausted) {
            ctx.onRetry(
                "upstream $realStatus is a quota exhaustion; treating it as " +
                    "${HttpStatus.TOO_MANY_REQUESTS} so the retry, cooldown and pool layers see the " +
                    "rate limit it is",
            )
        }
        return exhausted
    }
}

private const val MAX_ERROR_BODY_BYTES = 64 * 1024
