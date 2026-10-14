// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ pre-public-port-baseline — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); the request's WRITE is bounded by the long
// firstByteTimeout (V4-272, RequestWriteBound: a request the upstream acknowledges no more of for that
// long is cut, named RequestWriteStalled and retried here on a new connection, V4-289), while the wait
// for headers after a whole request is bounded only by the turn cap (a near-window prompt / compaction prefills
// for minutes); the body phase is governed by the stream watchdog, NOT here; retry on 502/503/529/429 with exponential
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
package splice.upstream.transport

import io.ktor.client.HttpClient
import splice.core.usage.PlanLimit
import splice.core.util.ERR_SNIPPET
import splice.core.util.ElapsedClock
import splice.upstream.RetryBackoff
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.UpstreamHandler
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.retry.CredentialCooldowns
import splice.upstream.retry.MAX_STREAM_REISSUES
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RateLimitTurn
import splice.upstream.retry.ReissueRules
import splice.upstream.retry.RetryDecision
import splice.upstream.retry.RetryPlan

public class UpstreamClient(
    private val totalTimeoutMs: Long,
    private val maxRetries: Int,
    /** zstd-compress the request body (CX-03). DEFAULT OFF and set PER PROVIDER: measured on
     *  codex-cli 0.145.0 against ChatGPT (2.7x), unproven anywhere else, and the sibling gzip ban
     *  exists because xAI 400d on a compressed body and broke grok live on 2026-07-18. */
    zstdRequestBody: Boolean = false,
    client: HttpClient = UpstreamTransport().defaultClient(totalTimeoutMs),
    /** How attempts are spaced: the curve this client budgets against and the two sleeps that follow it
     *  (HD-19, V4-110; [RetryPacing] has the reasoning). */
    private val pacing: RetryPacing = RetryPacing(),
    // Default is monotonic — a wall-clock jump must not abort a healthy retry loop (forward) or
    // extend its deadline (backward). Same base as TurnWatchdog/InflightGate: two authorities
    // enforce cfg.upstreamTimeoutMs and MUST NOT split-brain across clock bases (review 2026-07-22).
    private val clock: ElapsedClock = ProcessElapsedNow(),
    /** V4-412: where this head's provider hold (its reset, the plan window it named spent) survives a
     *  restart; null keeps it in memory only. Pool accounts each carry their own. */
    holdStore: ProviderHoldStore? = null,
    /** The tries a request gets, asked once as the request begins so it keeps one bound to its end; null keeps
     *  [maxRetries], the number the client was built with. */
) {
    // The stateless collaborators the loop delegates to. Constructed once per client (not per call)
    // so the transport/request/failure/retry rules cost nothing per attempt. [cooldown] is the one
    // that is NOT stateless — it holds the shared 429 horizon — and it takes THIS client's [clock],
    // never a second one.
    private val transportFailures = TransportFailures()
    private val request = UpstreamRequest(client, zstdRequestBody)

    /** The head's configured retry curve as a sleep, for the runners that restart a round (fold continuation,
     *  mid-stream re-anchor): one curve per head, the one an ordinary retry here already follows. */
    public val retryBackoff: RetryBackoff = RetryBackoff { attempt, minDelayMs -> pacing.pause(attempt, minDelayMs) }

    // Legacy files have no proved credential owner. Keep them untouched, never attribute their hold to a caller.
    private val cooldown = RateLimitCooldown(clock)
    private val credentialCooldowns = CredentialCooldowns(clock, holdStore)

    /** Resolves the actual credential's hold, never the aggregate head pressure reported by status. */
    public fun credentialCooldown(headers: Map<String, String>, declaredCarrier: String? = null): RateLimitCooldown? =
        credentialCooldowns.forHeaders(headers, declaredCarrier)
    private val budget = RetryBudget(totalTimeoutMs, clock)
    private val reissueRules = ReissueRules()

    /** NF-01: head restart is a real escape hatch — HeadServer.startLocked() clears the armed
     *  horizon alongside driver.resetHealth(), instead of the cooldown outliving the restart. */
    public fun clearRateLimitCooldown() {
        cooldown.clear()
        credentialCooldowns.clear()
    }

    /** NF-01: remaining armed cooldown (0 when idle) — surfaced so doctor/status views can name
     *  WHY a head is failing fast (NF-10/JW-11 read this). */
    public val rateLimitedForMs: Long get() = maxOf(cooldown.remainingMs(), credentialCooldowns.remainingMs)

    /** V4-50: how long the PROVIDER says it stays limited (0 when unknown) — the operator's real
     *  deadline, which is a different number from [rateLimitedForMs]. That one is splice's own
     *  follower-protection horizon, clamped to MAX_RATE_LIMIT_COOLDOWN_MS; this one is the reset the
     *  429 body actually named, and it is the only one worth telling a client to come back at. */
    public val providerResetForMs: Long
        get() = maxOf(cooldown.providerUnavailableForMs(), credentialCooldowns.providerResetForMs)

    /** V4-233: how long the upstream's named PLAN window stays spent (0 when none is held). */
    public val planHoldForMs: Long get() = maxOf(cooldown.planHold.forMs(), credentialCooldowns.planHoldForMs)

    /** V4-233: the held plan window exactly as the upstream named it, or null. The admission plane
     *  hands its reset to the client instead of the cooldown's lift, because it is the upstream's own
     *  statement and not a burst's stamp. */
    public val planHold: PlanLimit?
        get() = listOfNotNull(cooldown.planHold.live(), credentialCooldowns.planHold)
            .maxByOrNull(PlanLimit::resetEpochSeconds)

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
        round: RoundBody,
        block: UpstreamHandler<T>,
    ): UpstreamPost<T> = post(ctx, preparation(round), block)

    /** Prepare an unchanged body for account handoffs within one operation, never a client-wide cache.
     *  A transformed body needs its own preparation. Credentials and eligibility are not retained. */
    public val preparation: PostPreparation = PostPreparation(this, request)

    /** Reuse the original encoding across account handoffs. Each call still resolves credentials and
     *  budgets anew; an amendment replaces only this call's body, not the prepared original. */
    public suspend fun <T> post(
        ctx: PostContext,
        prepared: PreparedPost,
        block: UpstreamHandler<T>,
    ): UpstreamPost<T> {
        var body = ctx.perf?.timed(splice.core.perf.PerfKeys.OUTBOUND_PREP_MS) {
            prepared.forClient(this, ctx.observers)
        } ?: prepared.forClient(this, ctx.observers)
        val t0 = clock()
        val state = RetryState(ctx.limits.rateLimitCooldown ?: cooldown, t0, pacing.triesFor(maxRetries))
        while (state.attempt < state.maxRetries) {
            when (val step = runAttempt(ctx, body, state, t0, block)) {
                is LoopStep.Done -> return step.result
                is LoopStep.Amend -> {
                    prepared.amended(body)
                    body = request.body(RoundBody.Text(step.bodyJson)).also { it.bytes }
                }
                LoopStep.Continue -> Unit
                LoopStep.TurnWaitExhausted -> return UpstreamPost.TurnWaitExhausted
            }
        }
        return UpstreamPost.Ended(
            state.rules.giveUp(state.lastErr, state.cooldown, state.attempt, ctx.onRetry, state.refreshedOnce),
        )
    }

    /** One retry-loop iteration: the deadline and turn-wait checks, then credentials, the hold they
     *  pick ([RetryState.held]), and the attempt's retry/backoff decision. Each ending is a returned
     *  [LoopStep.Done], never a throw. Body preparation remains outside these attempt budgets. */
    private suspend fun <T> runAttempt(
        ctx: PostContext,
        body: RequestBody,
        state: RetryState,
        t0: Long,
        block: UpstreamHandler<T>,
    ): LoopStep<T> {
        if (budget.deadlineExceeded(ctx, t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before attempt " +
                    "${state.attempt + 1}/${state.maxRetries}",
            )
            return state.gaveUp(ctx, state.rules)
        }
        if (budget.turnWaitExhausted(ctx)) {
            ctx.onRetry("upstream turn wait budget exhausted before attempt ${state.attempt + 1}/${state.maxRetries}")
            return if (state.lastErr != null) state.gaveUp(ctx, state.rules) else LoopStep.TurnWaitExhausted
        }
        val auth = state.credentials(ctx, credentialCooldowns, cooldown)
        return if (auth == null) {
            LoopStep.Done(UpstreamPost.Ended(UpstreamAuthMissing()))
        } else {
            state.held(ctx) ?: attempt(ctx, body, state, block, auth)
        }
    }

    private suspend fun <T> attempt(
        ctx: PostContext,
        body: RequestBody,
        state: RetryState,
        block: UpstreamHandler<T>,
        auth: AttemptCredentials,
    ): LoopStep<T> {
        val t0 = state.t0
        ctx.markAttempt()
        val recorder = state.recorderFor(ctx, body, clock)
        var streamHandedOff = false
        // catchCancellable rethrows CancellationException (a cancelled turn aborts cleanly);
        // a failure here is a TRANSPORT error thrown BEFORE stream handoff — retryable on the
        // backoff budget (a 2s DNS blip costs one silent retry, not a turn failure: the kimi
        // 07:00 burst, 37 UnresolvedAddressException turns, attempts=1 on every one).
        // V4-66: "a TRANSPORT error" means every failure this seam can carry, not only the six
        // types the classifier names — a bare IOException from the JDK parser used to escape
        // with attempts=1 and end a turn a retry would have completed.
        val attempted = transportFailures.catchCancellable {
            state.markResend(ctx)
            request.execute(
                ctx,
                AttemptWire(auth.credentials, auth.headers, body.bytes, recorder),
                auth,
                onStreamStart = { streamHandedOff = true },
                block,
            )
        }
        val transportError = attempted.exceptionOrNull()
        val outcome = attempted.getOrNull()
        val read = (outcome as? RetryOutcome.Done)?.read
        // An oversized frame leaves no wire record: it never did, and this change moves no behavior (issue #406).
        if (read !is StreamRead.Oversized) {
            state.report(ctx, recorder, transportError ?: (read as? StreamRead.Torn)?.cause)
        }
        // Exhaustively dispatch the attempt's delivered value, HTTP failure, or transport error.
        return when (outcome) {
            null -> onTransportError(checkNotNull(transportError), ctx, streamHandedOff, state, t0)
            is RetryOutcome.Done -> readStep(outcome.read, ctx, state, t0)
            is RetryOutcome.Failed -> failedStep(ctx, outcome, body, state, t0)
        }
    }

    /** What the handler made of the stream: a delivered value, a tear the loop may re-issue, or an oversized frame. */
    private suspend fun <T> readStep(read: StreamRead<T>, ctx: PostContext, state: RetryState, t0: Long): LoopStep<T> =
        when (read) {
            is StreamRead.Read -> LoopStep.Done(UpstreamPost.Delivered(read.value))
            is StreamRead.Torn -> reissueStep(read.cause, ctx, true, state, t0)
                ?: LoopStep.Done(UpstreamPost.Ended(StreamTornBeforeClient(read.cause)))
            is StreamRead.Oversized -> LoopStep.Done(UpstreamPost.Ended(read.ending))
        }

    /** A native pooled refusal that waiting cannot fix leaves as a value before any consume callback, so the account
     *  handoff moves the request to the next login: a named plan window, or a pushback past the 15s ceiling. A burst
     *  429 keeps V4-61 like every other failure: it waits the floor on the same login, and relays once spent. A
     *  translated pool takes V4-61's plan first, which evicts the login, and the give-up hands it back
     *  ([RetryState.gaveUp]). */
    private suspend fun failedStep(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        body: RequestBody,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        state.lastErr = failed
        val nativeRefusal = ctx.nativePool && failed.rateLimitReply != null
        return if (nativeRefusal && failed.waitCannotHelp) {
            val failure = state.rules.rateLimitFailure(failed, state.cooldown, state.sent, ctx.onRetry)
            LoopStep.Done(UpstreamPost.Refused(failure))
        } else {
            state.amendStep(ctx, failed, body.json) ?: planStep(ctx, failed, state, t0)
        }
    }

    /** The transport-error half of one attempt, for a failure the JDK or the engine raised. A tear before the client
     *  saw a frame has its own reissue budget ([reissueStep]); otherwise the two give-up gates retain the existing
     *  pre-handoff transport retry policy, and a failure past them is rethrown as the raw transport error it is. */
    private suspend fun onTransportError(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        reissueStep(e, ctx, streamHandedOff, state, t0)?.let { return it }
        val phase = transportFailures.rethrowUnlessRetryableTransport(
            e,
            deadlineHit = streamHandedOff || budget.deadlineExceeded(ctx, t0),
            lastAttempt = state.attempt == state.maxRetries - 1,
        )
        val label = if (phase == TransportFailurePhase.POST_SEND) "transport-possible-duplicate" else "transport"
        ctx.onRetry(
            // V4-167: named like the ending names it — a refused connect's own message is empty.
            "$label ${e::class.simpleName} attempt ${state.attempt + 1}/${state.maxRetries}: " +
                TransportFailureReason.of(e, ctx.url).take(ERR_SNIPPET),
        )
        if (!applyTransportBackoff(e, ctx, state.attempt, t0)) throw e
        state.pendingRetry = if (phase == TransportFailurePhase.POST_SEND) RetryKind.POST_SEND else RetryKind.ORDINARY
        state.attempt += 1
        return LoopStep.Continue
    }

    /** G5: re-issue a stream torn before the client saw a byte, on its own budget. Null means the tear cannot be
     *  re-issued (no budget, not retryable, past the deadline, or no time for the wait), and the caller ends it. */
    private suspend fun reissueStep(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing>? {
        if (!reissuePermitted(e, ctx, streamHandedOff, state, t0)) return null
        state.streamReissues += 1
        ctx.onRetry(
            "stream torn before first client frame, reissue ${state.streamReissues}/$MAX_STREAM_REISSUES: " +
                "${e::class.simpleName} ${TransportFailureReason.of(e, ctx.url).take(ERR_SNIPPET)}",
        )
        if (!applyTransportBackoff(e, ctx, state.attempt, t0)) return null
        state.pendingRetry = RetryKind.ORDINARY
        return LoopStep.Continue // does NOT increment `attempt` — this budget is separate
    }

    private fun reissuePermitted(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        state: RetryState,
        t0: Long,
    ): Boolean {
        if (!reissueRules.canReissueStream(streamHandedOff, e, ctx.recovery.clientFrameEmitted, state.streamReissues)) {
            return false
        }
        // G4d: same re-check the sibling BACKOFF path (applyBackoff) does before its sleep — a
        // budget that expired mid-turn must not pay for one more real delay it can't use.
        if (!budget.deadlineExceeded(ctx, t0)) return true
        ctx.onRetry(
            "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before stream " +
                "reissue ${state.streamReissues + 1}/$MAX_STREAM_REISSUES",
        )
        return false
    }

    /** The BACKOFF half of the retry decision: re-checks the deadline (G4d) before the sleep so a
     *  budget that expired mid-curve doesn't pay for one more real delay it can't use. */
    private suspend fun applyBackoff(
        ctx: PostContext,
        plan: RetryPlan,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        if (budget.deadlineExceeded(ctx, t0)) {
            ctx.onRetry(
                "upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before backoff, " +
                    "attempt ${state.attempt + 1}/${state.maxRetries}",
            )
            return state.gaveUp(ctx, state.rules)
        }
        val plannedDelayMs = pacing.ordinaryDelayMs(state.attempt, plan.minDelayMs)
        if (!budget.backoffFits(ctx, t0, plannedDelayMs)) return state.gaveUp(ctx, state.rules)
        ctx.timedBackoff { pacing.pause(state.attempt, plan.minDelayMs) }
        state.pendingRetry = RetryKind.ORDINARY
        state.attempt += 1
        return LoopStep.Continue
    }

    /** Both transport paths budget the curve before sleeping; false means it does not fit and nothing slept. */
    private suspend fun applyTransportBackoff(e: Throwable, ctx: PostContext, attempt: Int, t0: Long): Boolean {
        val dns = transportFailures.isDnsFailureTransport(e)
        val plannedDelayMs = pacing.transportDelayMs(attempt, dns)
        if (!budget.backoffFits(ctx, t0, plannedDelayMs)) return false
        ctx.timedBackoff { pacing.pauseAfterTransportError(attempt, dns) }
        return true
    }

    /** RC-4 companion move (function-budget): the retry-plan tail of a failed attempt. */
    private suspend fun <T> planStep(
        ctx: PostContext,
        outcome: RetryOutcome.Failed,
        state: RetryState,
        t0: Long,
    ): LoopStep<T> {
        val plan = state.rules.planRetry(
            ctx,
            outcome,
            state.attempt,
            state.refreshedOnce,
            RateLimitTurn(
                cooldown = state.cooldown,
                pooledAccount = ctx.limits.rateLimitCooldown != null,
            ),
        )
        state.refreshedOnce = plan.refreshedOnce
        return when (plan.decision) {
            RetryDecision.RETRY -> LoopStep.Continue // refresh succeeded — no attempt spent
            RetryDecision.BACKOFF -> applyBackoff(ctx, plan, state, t0)
            RetryDecision.GIVE_UP -> state.gaveUp(ctx, state.rules)
        }
    }
}

/**
 * What [UpstreamClient.post] answers: the handler's value, or the one refusal the loop DECIDES.
 *
 * V4-114 (kt-no-exception-as-outcome): the exhausted turn-wait budget used to arrive as a thrown
 * `UpstreamTurnWaitExhausted`, which every `catch (e: Exception)` and `runCatching` on the turn
 * path saw as indistinguishable from a broken invariant, and which no signature announced. It is a
 * VALUE the one caller (SseRoundPost) hands to the translator, so the compiler now checks that the
 * caller handled it. Every other ending is a VALUE too since 2026-10-09: [Ended] carries the sealed
 * [UpstreamEnding] (the host's HTTP refusal after retries, a missing local credential, a torn transport, an
 * oversized frame), so no ending of [UpstreamClient.post] is thrown. Raw transport errors from the engine stay
 * exceptions: they are not splice's vocabulary.
 */
public sealed class UpstreamPost<out T> {
    /** Native pooled refusal before stream handoff. The caller may switch credentials, never resend this login. */
    public data class Refused(public val failure: UpstreamFailed) : UpstreamPost<Nothing>()

    /** The upstream answered and [value] is what the caller's handler made of it. */
    public data class Delivered<T>(public val value: T) : UpstreamPost<T>()

    /** The call ended without a round: a failure after retries, a hold, missing credentials, a tear that could not be
     *  re-issued, or an oversized frame. The caller ends the turn on [ending], whatever it is. */
    public data class Ended(public val ending: UpstreamEnding) : UpstreamPost<Nothing>()

    /** The whole-turn wait budget expired BEFORE a request went out, so there is no HTTP response
     *  to classify and nothing was sent. The caller owns the terminal. */
    public data object TurnWaitExhausted : UpstreamPost<Nothing>()
}
