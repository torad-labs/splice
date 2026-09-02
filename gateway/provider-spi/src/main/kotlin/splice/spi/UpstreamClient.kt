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
// WHERE THE REST WENT: client construction and the backoff curves → UpstreamTransport.kt;
// throwable classification, transport backoff, catchCancellable → TransportFailures.kt; the retry
// DECISION and the G5 re-issue interlock → RetryPolicy.kt; the failure predicates →
// FailureRules.kt; the Retry-After parse → RetryAfter.kt; the shared 429 horizon →
// RateLimitCooldown.kt; request assembly and the execute round-trip → UpstreamRequest.kt; one
// attempt's inputs, outcome, and the perf/auth marks → UpstreamAttempt.kt; the role declarations
// → UpstreamPorts.kt; the thrown vocabulary → UpstreamErrors.kt; the clock adapter →
// ProcessRuntime.kt.
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
    private val backoff: RetryBackoff = UpstreamTransport().defaultBackoff(waiter),
    private val dnsBackoff: DnsBackoff = UpstreamTransport().defaultDnsBackoff(waiter),
    // Default is monotonic — a wall-clock jump must not abort a healthy retry loop (forward) or
    // extend its deadline (backward). Same base as TurnWatchdog/InflightGate: two authorities
    // enforce cfg.upstreamTimeoutMs and MUST NOT split-brain across clock bases (review 2026-07-22).
    private val clock: ElapsedNow = ProcessElapsedNow(),
) {
    // The stateless collaborators the loop delegates to. Constructed once per client (not per call)
    // so the transport/request/failure/retry rules cost nothing per attempt. [cooldown] is the one
    // that is NOT stateless — it holds the shared 429 horizon — and it takes THIS client's [clock],
    // never a second one.
    private val transportFailures = TransportFailures()
    private val request = UpstreamRequest(client, zstdRequestBody)
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
     * and one single-flight 401 refresh. The credentials [ctx] supplies are written onto the
     * request by [UpstreamRequest]. Cancelling the calling coroutine aborts the in-flight body
     * (the lock-safe kill). When [PostContext.perf] is wired it records the auth/refresh/backoff
     * durations, the attempt counters, and the headers-arrival mark (TTFB — re-marked per attempt
     * so the successful attempt's value wins).
     */
    public suspend fun <T> post(
        ctx: PostContext,
        bodyJson: String,
        block: UpstreamHandler<T>,
    ): T {
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
        val creds = ctx.requireAuth()
        ctx.markAttempt()
        var streamHandedOff = false
        // catchCancellable rethrows CancellationException (a cancelled turn aborts cleanly);
        // a failure here is a TRANSPORT error thrown BEFORE stream handoff — retryable on the
        // backoff budget (a 2s DNS blip costs one silent retry, not a turn failure: the kimi
        // 07:00 burst, 37 UnresolvedAddressException turns, attempts=1 on every one).
        val attempted = try {
            transportFailures.catchCancellable {
                request.execute(ctx, body.bytes, creds, onStreamStart = { streamHandedOff = true }, block)
            }
        } catch (e: StreamTornBeforeClient) {
            // thrown by the turn driver through the translator (G5 reachability); a transport
            // failure like any other for the decision below — catchCancellable's I/O-only
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
            ctx.markRetry()
            ctx.timedBackoff { transportFailures.backoffTransportError(e, state.attempt, dnsBackoff, backoff) }
            return LoopStep.Continue // does NOT increment `attempt` — this budget is separate
        }
        val phase = transportFailures.rethrowUnlessRetryableTransport(
            e,
            deadlineHit = streamHandedOff || deadlineExceeded(t0),
            lastAttempt = state.attempt == maxRetries - 1,
        )
        val label = if (phase == TransportFailurePhase.POST_SEND) "transport-possible-duplicate" else "transport"
        ctx.onRetry(
            "$label ${e::class.simpleName} attempt ${state.attempt + 1}/$maxRetries: " +
                e.message.orEmpty().take(ERR_SNIPPET),
        )
        if (phase == TransportFailurePhase.POST_SEND) ctx.markPostSendRetry()
        ctx.markRetry()
        ctx.timedBackoff { transportFailures.backoffTransportError(e, state.attempt, dnsBackoff, backoff) }
        state.attempt += 1
        return LoopStep.Continue
    }

    /** The BACKOFF half of the retry decision: re-checks the deadline (G4d) before the sleep so a
     *  budget that expired mid-curve doesn't pay for one more real delay it can't use. */
    private suspend fun applyBackoff(
        ctx: PostContext,
        plan: RetryPlan,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        ctx.markRetry()
        if (deadlineExceeded(t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before backoff, " +
                    "attempt ${state.attempt + 1}/$maxRetries",
            )
            retryRules.giveUp(state.lastErr)
        }
        ctx.timedBackoff { backoff(state.attempt, plan.minDelayMs) }
        state.attempt += 1
        return LoopStep.Continue
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

// The width of an upstream error quoted into a retry notice. Read here and by RetryPolicy.kt's
// give-up / attempt notices, which quote the same failure text.
internal const val ERR_SNIPPET = 160
