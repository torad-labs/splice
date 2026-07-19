// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ 4ca99f7 — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); headers-phase (first-byte) timeout is the
// long firstByteTimeout (a near-window prompt / compaction prefills for minutes); the body phase
// is governed by the stream watchdog, NOT here; retry on 502/503/529/429 with exponential
// backoff; a 401 triggers a SINGLE single-flight refresh that does not consume a normal attempt;
// abort() is the only lock-safe kill (Ktor: cancel the calling coroutine → channel closes).
//
// Transport lessons from Grok Build / Codex CLI:
//   - shorter keepAlive than upstream idle so we don't reuse LB-killed sockets
//   - pipelineMaxSize=1 so a cancelled SSE cannot poison siblings
//   - request bodies are NEVER gzipped: xAI 400s on a gzipped body ("Failed to parse the
//     request body as JSON: expected value at line 1 column 1" — verified live 2026-07-18,
//     first >=2KiB turn after the gzip experiment deployed); ChatGPT is unproven. The body
//     still rides as pre-encoded UTF-8 bytes so retries never re-encode the string.
//   - encrypted_content decrypt 400s are NOT retried (Grok Build)
//   - DNS-class transport failures (UnresolvedAddressException/UnknownHostException) back off on
//     their own 1s/2s/4s schedule (dnsBackoff), not the generic 200/400/800ms curve (G14)
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.timedOr
import splice.core.util.runCatchingCancellable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

public class UpstreamClient(
    private val firstByteTimeoutMs: Long,
    private val totalTimeoutMs: Long,
    private val maxRetries: Int,
    private val client: HttpClient = defaultClient(firstByteTimeoutMs, totalTimeoutMs),
    // Exponential backoff, ±10% jitter (codex shape — synchronized retry herds re-collide without
    // it), capped at MAX_BACKOFF_MS; a server Retry-After rides in as a FLOOR via minDelayMs (G3).
    // DNS-class transport failures use dnsBackoff below instead (G14) — a resolver blip runs
    // longer than a TCP refusal.
    private val backoff: suspend (attempt: Int, minDelayMs: Long) -> Unit = { attempt, minDelayMs ->
        val base = minOf(BACKOFF_BASE_MS shl attempt, MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        delay(maxOf(jittered, minDelayMs))
    },
    // DNS-class transport failures (G14) get their own 1s/2s/4s schedule — a real resolver
    // blip (kimi 07:00 burst: 37 UnresolvedAddressException turns) runs longer than the
    // generic 200/400/800ms curve above undershoots. No minDelayMs parameter — transport
    // errors never carry a Retry-After header (no response was received).
    private val dnsBackoff: suspend (attempt: Int) -> Unit = { attempt ->
        val base = minOf(DNS_BACKOFF_BASE_MS shl attempt, DNS_MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        delay(jittered)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The per-post collaborators threaded through every attempt (grouped: one cohesive argument). */
    private data class PostContext(
        val url: String,
        val auth: RefreshableAuthProvider,
        val extraHeaders: suspend (Credentials) -> Map<String, String>,
        val onRetry: (String) -> Unit,
        val perf: TurnPerf?,
        val clientFrameEmitted: () -> Boolean,
    )

    /**
     * Prepare an upstream POST and run [block] with the streaming response. Handles retries
     * and one single-flight 401 refresh. [applyAuth] writes the current credentials onto the
     * request builder; [auth] supplies + refreshes them. Cancelling the calling coroutine
     * aborts the in-flight body (the lock-safe kill). When [perf] is wired it records the
     * auth/refresh/backoff durations, the attempt counters, and the headers-arrival mark
     * (TTFB — re-marked per attempt so the successful attempt's value wins).
     */
    public suspend fun <T> post(
        url: String,
        bodyJson: String,
        auth: RefreshableAuthProvider,
        extraHeaders: suspend (Credentials) -> Map<String, String>,
        onRetry: (String) -> Unit = {},
        perf: TurnPerf? = null,
        // Defaults to { true } — "assume the client already saw output" — so any caller that does
        // NOT wire this (there are none today besides TurnDriver, but keep the safe default) keeps
        // the pre-G5 commitment rule: never retry once handed off. Only TurnDriver, which can prove
        // FIRST_FRAME hasn't fired, passes a real probe.
        clientFrameEmitted: () -> Boolean = { true },
        block: suspend (UpstreamResponse) -> T,
    ): T {
        val ctx = PostContext(url, auth, extraHeaders, onRetry, perf, clientFrameEmitted)
        // Encode ONCE; retries resend the same bytes (no per-attempt string re-encode). Never gzip.
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val state = RetryState()
        val t0 = clock()
        while (state.attempt < maxRetries) {
            when (val step = runAttempt(ctx, bodyBytes, state, t0, block)) {
                is LoopStep.Done -> return step.value
                LoopStep.Continue -> Unit
            }
        }
        return giveUp(state.lastErr)
    }

    /** Mutable loop state threaded through [runAttempt] — extracted (with it) so `post()` stays
     *  under detekt's LongMethod/CyclomaticComplexMethod ceilings (G4d follow-up to bb8553f). */
    private class RetryState {
        var attempt: Int = 0
        var refreshedOnce: Boolean = false
        var lastErr: RetryOutcome.Failed? = null

        // G5: a small budget for re-issuing a stream torn BEFORE the client saw a byte. Spans the
        // whole turn (declared once here, never reset per handoff) and is deliberately smaller than
        // and independent of `maxRetries` — re-POSTing after a 2xx is a costlier, riskier act.
        var streamReissues: Int = 0
    }

    private sealed class LoopStep<out T> {
        data class Done<T>(val value: T) : LoopStep<T>()
        data object Continue : LoopStep<Nothing>()
    }

    /** One retry-loop iteration: a deadline check, the request attempt, and the retry/backoff
     *  decision. Split out of `post()` (same reasoning as planRetry/statusPlan) so the added
     *  cross-attempt deadline checks (G4d) don't push `post()` over the complexity ceiling.
     *  Deadline give-ups fall straight through [giveUp] (a `Nothing`-returning call, not a
     *  `return`) rather than signalling the loop to break — same funnel, no extra ReturnCount. */
    private suspend fun <T> runAttempt(
        ctx: PostContext,
        bodyBytes: ByteArray,
        state: RetryState,
        t0: Long,
        block: suspend (UpstreamResponse) -> T,
    ): LoopStep<T> {
        if (deadlineExceeded(t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before attempt " +
                    "${state.attempt + 1}/$maxRetries",
            )
            giveUp(state.lastErr)
        }
        val creds = ctx.perf.timedOr(PerfKeys.AUTH_MS) { ctx.auth.credentials() } ?: throw UpstreamAuthMissing()
        ctx.perf?.add(PerfKeys.ATTEMPTS, 1)
        var streamHandedOff = false
        // runCatchingCancellable rethrows CancellationException (a cancelled turn aborts cleanly);
        // a failure here is a TRANSPORT error thrown BEFORE stream handoff — retryable on the
        // backoff budget (a 2s DNS blip costs one silent retry, not a turn failure: the kimi
        // 07:00 burst, 37 UnresolvedAddressException turns, attempts=1 on every one).
        val attempted = try {
            runCatchingCancellable {
                attemptRequest(ctx, bodyBytes, creds, onStreamStart = { streamHandedOff = true }, block)
            }
        } catch (e: StreamTornBeforeClient) {
            // thrown by the turn driver through the translator (G5 reachability); a transport
            // failure like any other for the decision below — runCatchingCancellable's I/O-only
            // catch list can't see a RuntimeException, so it is folded in here.
            Result.failure(e)
        }
        val transportError = attempted.exceptionOrNull()
        if (transportError != null) {
            return onTransportError(transportError, ctx, streamHandedOff, state, t0)
        }
        val outcome = attempted.getOrThrow()
        if (outcome is RetryOutcome.Done) return LoopStep.Done(outcome.value)
        check(outcome is RetryOutcome.Failed) // sealed: Done or Failed, and Done returned above
        state.lastErr = outcome
        val plan = planRetry(ctx, outcome, state.attempt, state.refreshedOnce)
        state.refreshedOnce = plan.refreshedOnce
        return when (plan.decision) {
            RetryDecision.RETRY -> LoopStep.Continue // refresh succeeded — no normal attempt spent
            RetryDecision.BACKOFF -> applyBackoff(ctx, plan, state, t0)
            RetryDecision.GIVE_UP -> giveUp(state.lastErr)
        }
    }

    /** The transport-error half of one attempt (split so [runAttempt] stays under the complexity
     *  ceiling — same reasoning as planRetry/statusPlan). G5: a stream torn BEFORE the client saw a
     *  byte re-issues on its own small budget (does NOT consume `attempt`); otherwise the pre-G5
     *  rule holds — retry on the backoff budget only before handoff, else rethrow. */
    private suspend fun onTransportError(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        if (canReissueStream(streamHandedOff, e, ctx.clientFrameEmitted, state.streamReissues)) {
            state.streamReissues += 1
            ctx.onRetry(
                "stream torn before first client frame, reissue ${state.streamReissues}/$MAX_STREAM_REISSUES: " +
                    "${e::class.simpleName} ${e.message.orEmpty().take(ERR_SNIPPET)}",
            )
            ctx.perf?.add(PerfKeys.RETRIES, 1)
            backoffTransportError(ctx.perf, e, state.attempt)
            return LoopStep.Continue // does NOT increment `attempt` — this budget is separate
        }
        rethrowUnlessRetryableTransport(e, ctx, streamHandedOff, state.attempt, t0)
        ctx.perf?.add(PerfKeys.RETRIES, 1)
        backoffTransportError(ctx.perf, e, state.attempt)
        state.attempt += 1
        return LoopStep.Continue
    }

    /** Transport-error backoff (G14): DNS-class failures (name resolution never got an address)
     *  run the dedicated 1s/2s/4s dnsBackoff schedule instead of the generic curve — a resolver
     *  blip is slower than a TCP refusal or reset. */
    private suspend fun backoffTransportError(perf: TurnPerf?, error: Throwable, attempt: Int) {
        if (isDnsFailureTransport(error)) {
            perf.timedOr(PerfKeys.BACKOFF_MS) { dnsBackoff(attempt) }
        } else {
            perf.timedOr(PerfKeys.BACKOFF_MS) { backoff(attempt, 0L) }
        }
    }

    /** The BACKOFF half of the retry decision: re-checks the deadline (G4d) before the sleep so a
     *  budget that expired mid-curve doesn't pay for one more real delay it can't use. */
    private suspend fun applyBackoff(
        ctx: PostContext,
        plan: RetryPlan,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        ctx.perf?.add(PerfKeys.RETRIES, 1)
        if (deadlineExceeded(t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before backoff, " +
                    "attempt ${state.attempt + 1}/$maxRetries",
            )
            giveUp(state.lastErr)
        }
        ctx.perf.timedOr(PerfKeys.BACKOFF_MS) { backoff(state.attempt, plan.minDelayMs) }
        state.attempt += 1
        return LoopStep.Continue
    }

    /** The sole failure exit of the retry loop — carries the HTTP status so the classifier's
     *  429/401/5xx floors actually fire (body-text-only classification left them dead code). */
    private fun giveUp(last: RetryOutcome.Failed?): Nothing =
        throw UpstreamFailed(last?.text.orEmpty(), last?.status)

    // A transport error thrown BEFORE stream handoff (DNS/connect/timeout, per isRetryableTransport)
    // retries on the backoff budget; once the stream is handed off, the error is non-transport, or
    // it is the last attempt, rethrow — a retry would duplicate output or mask a real failure —
    // unless the stream was torn before the client saw any output — see canReissueStream (G5).
    private fun rethrowUnlessRetryableTransport(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        attempt: Int,
        t0: Long,
    ) {
        val phase = classifyTransport(e)
        val mustRethrow = streamHandedOff || phase == null || deadlineExceeded(t0)
        if (mustRethrow || attempt == maxRetries - 1) throw e
        // G16: SocketException/SocketTimeoutException can fire AFTER the request body has begun
        // or finished writing — the upstream may already have the POST and be processing/billing
        // it — unlike DNS/connect failures, which fire strictly before any byte leaves the client.
        // Same retry budget/backoff either way (diagnostics-only); the label makes a double-token-
        // burn incident greppable in the turn log instead of indistinguishable from a DNS blip.
        val label = if (phase == TransportFailurePhase.POST_SEND) "transport-possible-duplicate" else "transport"
        ctx.onRetry(
            "$label ${e::class.simpleName} attempt ${attempt + 1}/$maxRetries: " +
                e.message.orEmpty().take(ERR_SNIPPET),
        )
    }

    private suspend fun <T> attemptRequest(
        ctx: PostContext,
        bodyBytes: ByteArray,
        creds: Credentials,
        onStreamStart: () -> Unit,
        block: suspend (UpstreamResponse) -> T,
    ): RetryOutcome<T> {
        val allHeaders = applyAuth(creds, ctx.extraHeaders(creds))
        val statement = client.preparePost(ctx.url) {
            contentType(ContentType.Application.Json)
            headers { allHeaders.forEach { (k, v) -> append(k, v) } }
            setBody(ByteArrayContent(bodyBytes, ContentType.Application.Json))
        }
        return statement.execute { resp ->
            ctx.perf?.mark(PerfKeys.HEADERS)
            if (resp.status.isSuccess()) {
                onStreamStart() // block owns the stream from here — transport errors stop retrying
                RetryOutcome.Done(block(UpstreamResponse(resp)))
            } else {
                RetryOutcome.Failed(resp.status.value, resp.bodyText(), retryAfterMs(resp.headers["Retry-After"]))
            }
        }
    }

    /** Seconds-form Retry-After → ms; HTTP-date form and garbage → null (backoff curve decides). */
    private fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(MS_PER_S)

    /** Cross-attempt wall-clock budget (route-timeout analog to the per-try [firstByteTimeoutMs]). */
    private fun deadlineExceeded(t0: Long): Boolean = clock() - t0 >= totalTimeoutMs

    private suspend fun planRetry(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        refreshedOnce: Boolean,
    ): RetryPlan {
        // Grok Build: encrypted_content decrypt failures must not spin retries.
        if (isEncryptedContentError(failed.status, failed.text)) {
            ctx.onRetry(
                "upstream ${failed.status} encrypted_content error (no retry): " +
                    failed.text.take(ERR_SNIPPET),
            )
            return RetryPlan(RetryDecision.GIVE_UP, refreshedOnce)
        }
        val refreshable = isAuthRefreshableFailure(failed.status, failed.text) && !refreshedOnce
        if (refreshable) ctx.perf?.add(PerfKeys.REFRESHES, 1)
        if (refreshable && ctx.perf.timedOr(PerfKeys.REFRESH_MS) { ctx.auth.refresh() } != null) {
            return RetryPlan(RetryDecision.RETRY, refreshedOnce = true)
        }
        ctx.onRetry(
            "upstream ${failed.status} attempt ${attempt + 1}/$maxRetries: " +
                failed.text.take(ERR_SNIPPET),
        )
        return statusPlan(ctx, failed, attempt, refreshedOnce || refreshable)
    }

    /** Status/pushback half of the retry decision (split from planRetry: complexity wall). */
    private fun statusPlan(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        nextRefreshed: Boolean,
    ): RetryPlan {
        // gRPC-A6-style negative pushback: a server explicitly asking us to wait longer than the
        // interactive budget means "go away", not "hammer me on a curve" — give up honestly.
        val pushback = failed.retryAfterMs
        if (pushback != null && pushback > RETRY_AFTER_GIVE_UP_MS) {
            ctx.onRetry("upstream ${failed.status} Retry-After ${pushback}ms exceeds interactive budget (no retry)")
            return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
        }
        val retryable = isRetryableStatus(failed.status)
        val decision = if (!retryable || attempt == maxRetries - 1) RetryDecision.GIVE_UP else RetryDecision.BACKOFF
        return RetryPlan(decision, refreshedOnce = nextRefreshed, minDelayMs = pushback ?: 0L)
    }

    private data class RetryPlan(
        val decision: RetryDecision,
        val refreshedOnce: Boolean,
        val minDelayMs: Long = 0L,
    )

    private fun applyAuth(creds: Credentials, extra: Map<String, String>): Map<String, String> {
        val base = when (creds) {
            is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
            is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        }
        return base + extra
    }

    private enum class RetryDecision { RETRY, BACKOFF, GIVE_UP }

    private sealed class RetryOutcome<out T> {
        data class Done<T>(val value: T) : RetryOutcome<T>()
        data class Failed(
            val status: Int,
            val text: String,
            val retryAfterMs: Long? = null,
        ) : RetryOutcome<Nothing>()
    }

    public companion object {
        private const val BACKOFF_BASE_MS = 200L
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val ERR_SNIPPET = 160

        // xAI reports an expired/revoked OAuth token as 403 `unauthenticated:bad-credentials`,
        // NOT 401 (grok-dead-head incident, 2026-07-18: refresh never fired, the head 403'd every
        // turn until manual re-login). 401 is always refreshable; 403 only when the body says
        // auth — a plan/permission 403 must not spend the single refresh.
        private val authBodyRe = Regex(
            "unauthenticated|bad-credentials|token (is )?(invalid|expired)|" +
                "(access|oauth2?) token could not be validated",
            RegexOption.IGNORE_CASE,
        )

        /** Does this upstream failure warrant the single-flight token refresh? */
        internal fun isAuthRefreshableFailure(status: Int, body: String): Boolean =
            status == UNAUTHORIZED || (status == FORBIDDEN && authBodyRe.containsMatchIn(body))

        private const val MAX_CAUSE_DEPTH = 8

        /** Walks the cause chain (Ktor wraps engine exceptions) looking for a match, bounded by
         *  MAX_CAUSE_DEPTH. Shared primitive so the retryable-transport and DNS-only predicates
         *  don't duplicate the loop. */
        private fun causeChainMatches(e: Throwable, predicate: (Throwable) -> Boolean): Boolean {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < MAX_CAUSE_DEPTH) {
                if (predicate(t)) return true
                t = t.cause
                depth++
            }
            return false
        }

        /** G16: which side of the request write the transport failure happened on — CONNECT never
         *  got a byte onto the wire (DNS/refused/connect-timeout); POST_SEND may have already
         *  handed the upstream a full request (SocketException reset, socket-level timeout) —
         *  retrying that one risks a double token burn, so it needs a distinct log class. */
        private enum class TransportFailurePhase { CONNECT, POST_SEND }

        /** Per-node classification, split out of [classifyTransport] so the loop shape and the
         *  allowlist `when` each stay under detekt's CyclomaticComplexMethod ceiling. */
        private fun transportPhaseOf(t: Throwable): TransportFailurePhase? = when {
            t is java.nio.channels.UnresolvedAddressException ||
                t is java.net.UnknownHostException ||
                t is java.net.ConnectException ||
                t is io.ktor.client.network.sockets.ConnectTimeoutException -> TransportFailurePhase.CONNECT
            t is java.net.SocketException ||
                t is java.net.SocketTimeoutException ||
                t is io.ktor.client.network.sockets.SocketTimeoutException -> TransportFailurePhase.POST_SEND
            else -> null
        }

        /** Walks the cause chain (Ktor wraps engine exceptions), same shape as [causeChainMatches],
         *  classifying the retryable set by phase instead of a bare Boolean. Deliberately
         *  conservative — everything else (TLS trust failures, protocol errors, the HttpTimeout
         *  plugin's overall budget) still fails the turn. Not an added/removed exception type —
         *  a pure reclassification of the existing retryable set (G16). */
        private fun classifyTransport(e: Throwable): TransportFailurePhase? {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < MAX_CAUSE_DEPTH) {
                transportPhaseOf(t)?.let { return it }
                t = t.cause
                depth++
            }
            return null
        }

        /**
         * Connection-phase failures worth a silent retry: name resolution, TCP connect/reset,
         * socket timeouts. Deliberately conservative — everything else (TLS trust failures,
         * protocol errors, the HttpTimeout plugin's overall budget) still fails the turn.
         * Walks the cause chain because Ktor wraps engine exceptions.
         */
        internal fun isRetryableTransport(e: Throwable): Boolean = classifyTransport(e) != null

        /** DNS-class only (name resolution never got an address) — a real resolver blip runs
         *  closer to 1-4s than a TCP refusal, so it gets its own schedule instead of racing the
         *  generic curve (G14; Envoy dns_failure_refresh_rate shape). */
        internal fun isDnsFailureTransport(e: Throwable): Boolean = causeChainMatches(e) { t ->
            t is java.nio.channels.UnresolvedAddressException || t is java.net.UnknownHostException
        }

        private const val MAX_STREAM_REISSUES = 2

        /** G5: a torn stream re-issues the request iff it was already handed off (2xx received),
         *  the client has NOT yet seen a byte (FIRST_FRAME unmarked — duplicate-output risk starts
         *  the instant it has), the failure is a retryable transport class, and the small dedicated
         *  budget isn't spent. Separate from the connect-phase `attempt`/`maxRetries` budget on
         *  purpose: re-issuing after a 2xx is a costlier, riskier act than a pre-handoff retry. */
        internal fun canReissueStream(
            streamHandedOff: Boolean,
            e: Throwable,
            clientFrameEmitted: () -> Boolean,
            streamReissues: Int,
        ): Boolean = streamHandedOff &&
            !clientFrameEmitted() &&
            isRetryableTransport(e) &&
            streamReissues < MAX_STREAM_REISSUES

        // Every surveyed harness (codex, gemini-cli, Claude Code) retries ALL 5xx; 501 stays
        // terminal (Not Implemented never heals) and 4xx stays terminal except 408/429 (G4a).
        private const val RATE_LIMITED = 429
        private const val REQUEST_TIMEOUT = 408
        private const val NOT_IMPLEMENTED = 501
        private val SERVER_ERRORS = 500..599

        private fun isRetryableStatus(status: Int): Boolean =
            status == RATE_LIMITED || status == REQUEST_TIMEOUT ||
                (status in SERVER_ERRORS && status != NOT_IMPLEMENTED)

        private const val MS_PER_S = 1000L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val RETRY_AFTER_GIVE_UP_MS = 60_000L
        private const val JITTER_LO = 0.9
        private const val JITTER_HI = 1.1

        // DNS-class transport failures (G14) get their own schedule — 1s/2s/4s.
        private const val DNS_BACKOFF_BASE_MS = 1_000L
        private const val DNS_MAX_BACKOFF_MS = 4_000L

        // G11: a blackholed/dead address must fail fast into the existing transport-retry loop
        // (isRetryableTransport) instead of stalling to the OS SYN timeout x maxRetries. Decoupled
        // from firstByteTimeoutMs (5min default), which governs headers-wait/body phase via
        // socketTimeoutMillis, not TCP connect.
        private const val CONNECT_TIMEOUT_MS = 10_000L

        // G26: java.net.http.HttpClient/Builder expose no public API to read or set TCP_NODELAY per
        // connection (confirmed via javap on ktor-client-java-jvm; JDK-8338681 is an open
        // enhancement request for exactly this, still unresolved). Reflecting into
        // jdk.internal.net.http internals is fragile/module-encapsulated and disproportionate for a
        // LOW one-time diagnostic — the honest move is to log that verification is impossible via
        // public API, once per JVM (a JVM-wide guard so N heads sharing one daemon log it once, not
        // N times each time a head is assembled). Injectable (same pattern as backoff/dnsBackoff/
        // clock above) so a test can pin its own guard instead of sharing process-wide state with
        // every other direct defaultClient() caller (UpstreamClientConnectTimeoutTest calls it too,
        // for its own unrelated real-socket connect-timeout probe).
        private val nodelayLogged = AtomicBoolean(false)

        public fun defaultClient(
            firstByteTimeoutMs: Long,
            totalTimeoutMs: Long,
            log: (String) -> Unit = {},
            noDelayGuard: AtomicBoolean = nodelayLogged,
        ): HttpClient {
            if (noDelayGuard.compareAndSet(false, true)) {
                log(
                    "[upstream] tcp_nodelay(client)=unverifiable: java.net.http.HttpClient exposes " +
                        "no public API to read or set TCP_NODELAY per connection (JDK-8338681, open)\n",
                )
            }
            return HttpClient(Java) {
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = totalTimeoutMs
                    socketTimeoutMillis = firstByteTimeoutMs
                }
                engine {
                    // JDK HttpClient (async NIO), replacing ktor CIO. CIO's socket writer busy-spun
                    // on a non-writable upstream socket (macOS/kqueue) and melted CPU under
                    // concurrent large-body streams — 9 writer coroutines pegged cores and starved
                    // the coroutine dispatcher to 103 workers (busy-loop jstack, 2026-07-18). The
                    // JDK engine parks on write backpressure and drives the 1000-stream target on a
                    // shared selector, not a thread-per-write. HTTP/1.1 only — parity with the CIO
                    // lineage (undici allowH2:false): one stream per connection, cancel-safe; the
                    // app-level retry loop already owns connect retries.
                    protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
                }
            }
        }

        private const val CLIENT_ERROR_MIN = 400
        private const val CLIENT_ERROR_MAX = 499

        /** Grok Build: 4xx + "encrypted_content" in the message → do not retry. */
        internal fun isEncryptedContentError(status: Int, body: String): Boolean =
            status in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX && body.contains("encrypted_content", ignoreCase = true)
    }
}

public class UpstreamAuthMissing : RuntimeException("no upstream credentials")

/** G5 reachability (review 2026-07-19): a transport tear BEFORE any client frame, rethrown by the
 *  turn driver THROUGH the translators (whose catch lists deliberately swallow IOException into
 *  the honest terminal — correct post-frame, but it made the reissue unreachable). Plain
 *  RuntimeException so no translator catch matches; the original tear rides as [cause] so
 *  isRetryableTransport's cause-chain walk classifies it. */
public class StreamTornBeforeClient(cause: Throwable) :
    RuntimeException("stream torn before first client frame", cause)

public class UpstreamFailed(
    public val body: String,
    public val status: Int? = null,
) : RuntimeException("upstream failed after retries (status=$status)")
