// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ pre-public-port-baseline — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); headers-phase (first-byte) timeout is the
// long firstByteTimeout (a near-window prompt / compaction prefills for minutes); the body phase
// is governed by the stream watchdog, NOT here; retry on 502/503/529/429 with exponential
// backoff; a 401 triggers a SINGLE single-flight refresh that does not consume a normal attempt;
// abort() is the only lock-safe kill (Ktor: cancel the calling coroutine → channel closes).
//
// WHAT IS LEFT HERE, after HD-25: the retry LOOP and nothing else. The loop's four budgets share
// one [RetryState] and every rule about them is a rule about which counter must NOT move, so they
// stay in one object mutated from one file — `attempt` (connect-phase backoff), `refreshedOnce`
// (the 401 single-flight refresh, which must not consume an attempt), `streamReissues` (G5, spans
// the whole turn) and `amendedOnce` (RC-4, budgeted alone; review 2026-07-24 found an `attempt +=
// 1` here ate a valid amended resend at the budget boundary). The t0 deadline and its four checks
// are here for the same reason: ONE clock authority, same base as TurnWatchdog/InflightGate
// (review 2026-07-22).
//
// WHERE THE REST WENT: client construction → UpstreamTransport.kt; throwable classification →
// TransportFailures.kt; the retry DECISION and the G5 re-issue interlock → RetryPolicy.kt; the
// failure predicates → FailureRules.kt; the
// Retry-After parse → RetryAfter.kt; the shared 429 horizon → RateLimitCooldown.kt; request
// assembly → UpstreamRequest.kt; one attempt's inputs and outcome → UpstreamAttempt.kt; the role
// declarations → UpstreamPorts.kt; the thrown vocabulary → UpstreamErrors.kt.
//
// Transport lessons from Grok Build / Codex CLI that still bind this file:
//   - encrypted_content decrypt 400s are NOT retried (Grok Build)
//   - DNS-class transport failures (UnresolvedAddressException/UnknownHostException) back off on
//     their own 1s/2s/4s schedule (dnsBackoff), not the generic 200/400/800ms curve (G14)
//   - 429s arm a SHARED per-client (= per-account) cooldown: one turn's rate-limit discovery
//     teaches every concurrent turn, which fails fast with 429 instead of independently burning
//     its own attempts (2026-07-19 storm: ~650 turns x 4 attempts against one limited account)
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.http.isSuccess
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.TurnPerfTiming
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import kotlin.random.Random

public class UpstreamClient(
    private val firstByteTimeoutMs: Long,
    private val totalTimeoutMs: Long,
    private val maxRetries: Int,
    /** zstd-compress the request body (CX-03). DEFAULT OFF and set PER PROVIDER: measured on
     *  codex-cli 0.145.0 against ChatGPT (2.7x), unproven anywhere else, and the sibling gzip ban
     *  exists because xAI 400d on a compressed body and broke grok live on 2026-07-18. */
    zstdRequestBody: Boolean = false,
    client: HttpClient = UpstreamTransport().defaultClient(firstByteTimeoutMs, totalTimeoutMs),
    // HD-19: the seam both backoff curves below sleep through. Declared BEFORE them so their default
    // values can close over it (a Kotlin default may reference an earlier parameter), which is what
    // lets a test replace the WAIT without also having to re-author the CURVE it is measuring —
    // wire a recording waiter and the 200/400/800ms schedule becomes an assertion on a list instead
    // of 1.4 seconds of real sleeping.
    waiter: Waiter = ProcessWaiter(),
    // Exponential backoff, ±10% jitter (codex shape — synchronized retry herds re-collide without
    // it), capped at MAX_BACKOFF_MS; a server Retry-After rides in as a FLOOR via minDelayMs (G3).
    // DNS-class transport failures use dnsBackoff below instead (G14) — a resolver blip runs
    // longer than a TCP refusal.
    private val backoff: RetryBackoff = RetryBackoff { attempt, minDelayMs ->
        val base = minOf(BACKOFF_BASE_MS shl attempt, MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        waiter.wait(maxOf(jittered, minDelayMs))
    },
    // DNS-class transport failures (G14) get their own 1s/2s/4s schedule — a real resolver
    // blip (kimi 07:00 burst: 37 UnresolvedAddressException turns) runs longer than the
    // generic 200/400/800ms curve above undershoots. No minDelayMs parameter — transport
    // errors never carry a Retry-After header (no response was received).
    private val dnsBackoff: DnsBackoff = DnsBackoff { attempt ->
        val base = minOf(DNS_BACKOFF_BASE_MS shl attempt, DNS_MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        waiter.wait(jittered)
    },
    // Default is monotonic — a wall-clock jump must not abort a healthy retry loop (forward) or
    // extend its deadline (backward). Same base as TurnWatchdog/InflightGate: two authorities
    // enforce cfg.upstreamTimeoutMs and MUST NOT split-brain across clock bases (review 2026-07-22).
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    // The stateless collaborators the loop delegates to. Constructed once per client (not per call)
    // so the transport/request/failure/retry rules cost nothing per attempt. [cooldown] is the one
    // that is NOT stateless — it holds the shared 429 horizon — and it takes THIS client's [clock],
    // never a second one.
    private val transportFailures = TransportFailures()
    private val request = UpstreamRequest(client, zstdRequestBody)
    private val retryAfter = RetryAfter()
    private val cooldown = RateLimitCooldown(clock)
    private val retryRules = RetryRules(maxRetries, cooldown)
    private val reissueRules = ReissueRules()

    /** NF-01: head restart is a real escape hatch — HeadServer.startLocked() clears the armed
     *  horizon alongside driver.resetHealth(), instead of the cooldown outliving the restart. */
    public fun clearRateLimitCooldown() {
        cooldown.clear()
    }

    /** NF-01: remaining armed cooldown (0 when idle) — surfaced so doctor/status views can name
     *  WHY a head is failing fast (NF-10/JW-11 read this). */
    public val rateLimitedForMs: Long get() = cooldown.remainingMs()

    /**
     * Prepare an upstream POST and run [block] with the streaming response. Handles retries
     * and one single-flight 401 refresh. The credentials [auth] supplies are written onto the
     * request by [UpstreamRequest]. Cancelling the calling coroutine aborts the in-flight body
     * (the lock-safe kill). When [perf] is wired it records the auth/refresh/backoff durations,
     * the attempt counters, and the headers-arrival mark (TTFB — re-marked per attempt so the
     * successful attempt's value wins).
     */
    public suspend fun <T> post(
        url: String,
        bodyJson: String,
        auth: RefreshableAuthProvider,
        extraHeaders: CredentialHeaders,
        onRetry: RetryNotice = RetryNotice {},
        perf: TurnPerf? = null,
        // Defaults to { true } — "assume the client already saw output" — so any caller that does
        // NOT wire this (there are none today besides TurnDriver, but keep the safe default) keeps
        // the pre-G5 commitment rule: never retry once handed off. Only TurnDriver, which can prove
        // FIRST_FRAME hasn't fired, passes a real probe.
        clientFrameEmitted: ClientFrameEmitted = ClientFrameEmitted { true },
        // RC-4 (reasoning-cache 2026-07-24): a caller-supplied ONE-SHOT body amendment for
        // deterministic upstream rejections of request CONTENT (e.g. a 400 for stale
        // encrypted-reasoning items): (status, responseText, currentBodyJson) -> amended body
        // or null. Non-null swaps the body and retries immediately; fires at most once per post.
        amendBodyOnFailure: BodyAmendment = BodyAmendment { _, _, _ -> null },
        block: UpstreamHandler<T>,
    ): T {
        val ctx = PostContext(url, auth, extraHeaders, onRetry, perf, clientFrameEmitted, amendBodyOnFailure)
        // Encode ONCE; retries resend the same bytes (no per-attempt string re-encode). Never gzip.
        var body = request.body(bodyJson)
        val state = RetryState()
        val t0 = clock()
        while (state.attempt < maxRetries) {
            when (val step = runAttempt(ctx, body, state, t0, block)) {
                is LoopStep.Done -> return step.value
                is LoopStep.Amend -> body = request.body(step.bodyJson)
                LoopStep.Continue -> Unit
            }
        }
        return retryRules.giveUp(state.lastErr)
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

        // RC-4: the one-shot body-amendment budget (a deterministic 400 amended twice is a loop).
        var amendedOnce: Boolean = false

        /** RC-4: the one-shot amendment decision — lives here because it is pure retry-state
         *  bookkeeping. Budgeted by [amendedOnce] ALONE, never the attempt counter: the amended
         *  resend replays the failed attempt's slot, so it is guaranteed to go out even when the
         *  400 lands on the last permitted attempt (review 2026-07-24: an `attempt += 1` here made
         *  the loop guard eat the resend at the budget boundary — the amend computed a valid body
         *  and then gave up on the stale pre-amendment error). */
        fun amendStep(ctx: PostContext, outcome: RetryOutcome.Failed, bodyJson: String): LoopStep.Amend? {
            if (amendedOnce) return null
            val amended = ctx.amendBodyOnFailure(outcome.status, outcome.text, bodyJson) ?: return null
            amendedOnce = true
            ctx.onRetry("amending request body after ${outcome.status} and retrying once")
            return LoopStep.Amend(amended)
        }
    }

    private sealed class LoopStep<out T> {
        data class Done<T>(val value: T) : LoopStep<T>()
        data class Amend(val bodyJson: String) : LoopStep<Nothing>()
        data object Continue : LoopStep<Nothing>()
    }

    /** One retry-loop iteration: a deadline check, the request attempt, and the retry/backoff
     *  decision. Split out of `post()` (same reasoning as planRetry/statusPlan) so the added
     *  cross-attempt deadline checks (G4d) don't push `post()` over the complexity ceiling.
     *  Deadline give-ups fall straight through [RetryRules.giveUp] (a `Nothing`-returning call, not
     *  a `return`) rather than signalling the loop to break — same funnel, no extra ReturnCount. */
    private suspend fun <T> runAttempt(
        ctx: PostContext,
        body: RequestBody,
        state: RetryState,
        t0: Long,
        block: UpstreamHandler<T>,
    ): LoopStep<T> {
        if (deadlineExceeded(t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before attempt " +
                    "${state.attempt + 1}/$maxRetries",
            )
            retryRules.giveUp(state.lastErr)
        }
        cooldown.failFastIfArmed(ctx.onRetry)
        val creds = TurnPerfTiming.timedOr(ctx.perf, PerfKeys.AUTH_MS) { ctx.auth.credentials() }
            ?: throw UpstreamAuthMissing()
        ctx.perf?.add(PerfKeys.ATTEMPTS, 1)
        var streamHandedOff = false
        // runCatchingCancellable rethrows CancellationException (a cancelled turn aborts cleanly);
        // a failure here is a TRANSPORT error thrown BEFORE stream handoff — retryable on the
        // backoff budget (a 2s DNS blip costs one silent retry, not a turn failure: the kimi
        // 07:00 burst, 37 UnresolvedAddressException turns, attempts=1 on every one).
        val attempted = try {
            Cancellables.runCatchingCancellable {
                attemptRequest(ctx, body.bytes, creds, onStreamStart = { streamHandedOff = true }, block)
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
        // RC-4: a one-shot content amendment outranks the normal retry plan — a deterministic
        // 400 (stale encrypted reasoning) would otherwise GIVE_UP; the amended body gets exactly
        // one immediate resend, then normal classification owns whatever happens next.
        return state.amendStep(ctx, outcome, body.json) ?: planStep(ctx, outcome, state, t0)
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
        if (reissueRules.canReissueStream(streamHandedOff, e, ctx.clientFrameEmitted, state.streamReissues)) {
            // G4d: same re-check the sibling BACKOFF path (applyBackoff) does before its sleep — a
            // budget that expired mid-turn must not pay for one more real delay it can't use.
            if (deadlineExceeded(t0)) {
                ctx.onRetry(
                    "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before stream " +
                        "reissue ${state.streamReissues + 1}/$MAX_STREAM_REISSUES",
                )
                throw e
            }
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
        if (transportFailures.isDnsFailureTransport(error)) {
            TurnPerfTiming.timedOr(perf, PerfKeys.BACKOFF_MS) { dnsBackoff(attempt) }
        } else {
            TurnPerfTiming.timedOr(perf, PerfKeys.BACKOFF_MS) { backoff(attempt, 0L) }
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
            retryRules.giveUp(state.lastErr)
        }
        TurnPerfTiming.timedOr(ctx.perf, PerfKeys.BACKOFF_MS) { backoff(state.attempt, plan.minDelayMs) }
        state.attempt += 1
        return LoopStep.Continue
    }

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
        val phase = transportFailures.classifyTransport(e)
        val mustRethrow = streamHandedOff || phase == null || deadlineExceeded(t0)
        if (mustRethrow || attempt == maxRetries - 1) throw e
        // G16: SocketException/SocketTimeoutException can fire AFTER the request body has begun
        // or finished writing — the upstream may already have the POST and be processing/billing
        // it — unlike DNS/connect failures, which fire strictly before any byte leaves the client.
        // Same retry budget/backoff either way; the label makes a double-token-burn incident
        // greppable in the turn log, and UP-004's POST_SEND_RETRIES counter makes its rate
        // countable in the perf row instead of only greppable.
        val label = if (phase == TransportFailurePhase.POST_SEND) "transport-possible-duplicate" else "transport"
        if (phase == TransportFailurePhase.POST_SEND) ctx.perf?.add(PerfKeys.POST_SEND_RETRIES, 1)
        ctx.onRetry(
            "$label ${e::class.simpleName} attempt ${attempt + 1}/$maxRetries: " +
                e.message.orEmpty().take(ERR_SNIPPET),
        )
    }

    /** The READ-BEFORE-CLOSE half of one attempt. Status, error body and Retry-After are all
     *  extracted INSIDE the execute block because the response body channel dies at its close —
     *  which is why only the ASSEMBLY above the `execute` call could move to UpstreamRequest.kt. */
    private suspend fun <T> attemptRequest(
        ctx: PostContext,
        bodyBytes: ByteArray,
        creds: Credentials,
        onStreamStart: StreamStart,
        block: UpstreamHandler<T>,
    ): RetryOutcome<T> {
        val statement = request.prepare(ctx.url, creds, ctx.extraHeaders, bodyBytes)
        return statement.execute { resp ->
            ctx.perf?.mark(PerfKeys.HEADERS)
            if (resp.status.isSuccess()) {
                onStreamStart() // block owns the stream from here — transport errors stop retrying
                RetryOutcome.Done(block(UpstreamResponse(resp)))
            } else {
                RetryOutcome.Failed(
                    resp.status.value,
                    UpstreamResponse(resp).bodyTextLimited(MAX_ERROR_BODY_BYTES),
                    retryAfter.retryAfterMs(resp.headers["Retry-After"]),
                )
            }
        }
    }

    /** Cross-attempt wall-clock budget (route-timeout analog to the per-try [firstByteTimeoutMs]). */
    private fun deadlineExceeded(t0: Long): Boolean = clock() - t0 >= totalTimeoutMs

    /** RC-4 companion move (function-budget): the retry-plan tail of a failed attempt. */
    private suspend fun <T> planStep(
        ctx: PostContext,
        outcome: RetryOutcome.Failed,
        state: RetryState,
        t0: Long,
    ): LoopStep<T> {
        val plan = retryRules.planRetry(ctx, outcome, state.attempt, state.refreshedOnce)
        state.refreshedOnce = plan.refreshedOnce
        return when (plan.decision) {
            RetryDecision.RETRY -> LoopStep.Continue // refresh succeeded — no attempt spent
            RetryDecision.BACKOFF -> applyBackoff(ctx, plan, state, t0)
            RetryDecision.GIVE_UP -> retryRules.giveUp(state.lastErr)
        }
    }
}

private const val BACKOFF_BASE_MS = 200L

// The width of an upstream error quoted into a retry notice. Read here and by RetryPolicy.kt's
// give-up / attempt notices, which quote the same failure text.
internal const val ERR_SNIPPET = 160

private const val MAX_ERROR_BODY_BYTES = 64 * 1024

private const val MAX_BACKOFF_MS = 10_000L

private const val JITTER_LO = 0.9
private const val JITTER_HI = 1.1

// DNS-class transport failures (G14) get their own schedule — 1s/2s/4s.
private const val DNS_BACKOFF_BASE_MS = 1_000L
private const val DNS_MAX_BACKOFF_MS = 4_000L
