// PORT-OF: server/src/upstream/{fetch,gate}.mjs retry loop @ pre-public-port-baseline — invariants: one shared
// HTTP/1.1 client (undici allowH2:false → Ktor CIO); the request's WRITE is bounded by the long
// firstByteTimeout (V4-272, RequestWriteBound: a request the upstream acknowledges no more of for that
// long is cut, named RequestWriteStalled and retried here on a new connection, V4-289), while the wait
// for headers after a whole request is bounded only by the turn cap (a near-window prompt / compaction prefills
// for minutes); the body phase is governed by the stream watchdog, NOT here; abort() is the only lock-safe kill
// (Ktor: cancel the calling coroutine → channel closes).
//
// WHAT IS LEFT HERE: the attempt LOOP and nothing else. Failure-handling slice 4: the loop decides no retry of its
// own. Each failure an attempt meets is asked of RetryEngine, with where the turn stood and the turn's one
// RetryLedger, and RetrySteps takes the step it answers: a wait, one token exchange, a move to another login, or the
// post's ending. The t0 deadline and its checks stay here: ONE clock authority, same base as TurnWatchdog/InflightGate
// (review 2026-07-22).
//
// WHERE THE REST WENT: client construction → UpstreamTransport.kt; throwable classification and catchCancellable →
// TransportFailures.kt; the retry DECISION → RetryEngine.kt, its budgets → RetryBudgets.kt, the steps it answers →
// RetrySteps.kt and the post's endings → RetryEndings.kt; the reading of a failed reply → the head's Replies, asked
// once in UpstreamRequest.kt; the Retry-After parse → RetryAfter.kt; the shared 429 horizon → RateLimitCooldown.kt;
// request assembly and the execute round-trip → UpstreamRequest.kt; one attempt's inputs, outcome, and the perf/auth
// marks → UpstreamAttempt.kt; the role declarations → UpstreamPorts.kt; the thrown vocabulary → UpstreamErrors.kt;
// the clock adapter → ProcessRuntime.kt; what post answers → UpstreamPost.kt.
//
// Transport lessons from Grok Build / Codex CLI that still bind this file:
//   - encrypted_content decrypt 400s are NOT retried (Grok Build): read as stale reasoning, which the dialect amends
//     once and the engine ends after
//   - 429s arm a SHARED per-client (= per-account) cooldown: one turn's rate-limit discovery
//     teaches every concurrent turn (2026-07-19 storm: ~650 turns x 4 attempts against one limited account)
package splice.upstream.transport

import io.ktor.client.HttpClient
import splice.core.auth.LoginAnswer
import splice.core.failure.FailureEvidence
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.TurnRetry
import splice.upstream.TurnWaits
import splice.upstream.UpstreamHandler
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.retry.CredentialCooldowns
import splice.upstream.retry.MAX_ATTEMPTS_OUTSIDE_HOLDS
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryLedger

public class UpstreamClient(
    private val totalTimeoutMs: Long,
    /** zstd-compress the request body (CX-03). DEFAULT OFF and set PER PROVIDER: measured on
     *  codex-cli 0.145.0 against ChatGPT (2.7x), unproven anywhere else, and the sibling gzip ban
     *  exists because xAI 400d on a compressed body and broke grok live on 2026-07-18. */
    zstdRequestBody: Boolean = false,
    client: HttpClient = UpstreamTransport().defaultClient(totalTimeoutMs),
    /** How this client waits and dates: the wait a post with no turn around it sleeps through, and the wall clock a
     *  failed answer is dated by ([RetryPacing]). */
    private val pacing: RetryPacing = RetryPacing(),
    // Default is monotonic — a wall-clock jump must not abort a healthy retry loop (forward) or
    // extend its deadline (backward). Same base as TurnWatchdog/InflightGate: two authorities
    // enforce cfg.upstreamTimeoutMs and MUST NOT split-brain across clock bases (review 2026-07-22).
    private val clock: ElapsedClock = ProcessElapsedNow(),
    /** V4-412: where this head's provider hold (its reset, the plan window it named spent) survives a
     *  restart; null keeps it in memory only. Pool accounts each carry their own. */
    holdStore: ProviderHoldStore? = null,
) {
    // The stateless collaborators the loop delegates to. Constructed once per client (not per call)
    // so the transport/request/failure/retry rules cost nothing per attempt. [cooldown] is the one
    // that is NOT stateless — it holds the shared 429 horizon — and it takes THIS client's [clock],
    // never a second one.
    private val transportFailures = TransportFailures()
    private val request = UpstreamRequest(client, zstdRequestBody, pacing.wall)

    /** The wall clock a failed answer is dated by ([RetryPacing]): the head's round controller asks RetryEngine on it
     *  too, so a time an answer stated reads the same to both executors of the engine's steps. */
    public val retryWall: WallClock get() = pacing.wall

    /** Where a wait RetryEngine names sleeps ([RetryPacing]'s waiter): a turn's waits at its head sleep here too, so a
     *  test that replaces this client's wait replaces every wait a retry of its turns makes (HD-19). */
    public val retryWaiter: Waiter = Waiter { ms -> pacing.wait(ms) }

    // Legacy files have no proved credential owner. Keep them untouched, never attribute their hold to a caller.
    private val cooldown = RateLimitCooldown(clock, pacing.wall)
    private val credentialCooldowns = CredentialCooldowns(clock, holdStore, pacing.wall)

    /** Resolves the actual credential's hold, never the aggregate head pressure reported by status. */
    public fun credentialCooldown(headers: Map<String, String>, declaredCarrier: String? = null): RateLimitCooldown? =
        credentialCooldowns.forHeaders(headers, declaredCarrier)
    private val budget = RetryBudget(totalTimeoutMs, clock)
    private val steps = RetrySteps(budget, pacing.wall, transportFailures)

    /** NF-01: head restart is a real escape hatch — HeadServer.startLocked() clears the armed
     *  horizon alongside driver.resetHealth(), instead of the cooldown outliving the restart. */
    public fun clearRateLimitCooldown() {
        cooldown.clear()
        credentialCooldowns.clear()
    }

    /** NF-01: how long a login still reads limited (0 when idle) — surfaced so doctor/status views can
     *  name why (NF-10/JW-11 read this). It never holds a turn: each asks the provider itself. */
    public val rateLimitedForMs: Long get() = maxOf(cooldown.remainingMs(), credentialCooldowns.remainingMs)

    /** V4-50: how long the PROVIDER says it stays limited (0 when unknown) — the operator's real
     *  deadline, which is a different number from [rateLimitedForMs]. That one is splice's own
     *  horizon on the login, clamped to MAX_RATE_LIMIT_COOLDOWN_MS; this one is the reset the
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
     * Prepare an upstream POST and run [block] with the streaming response. Every failure an attempt meets is asked
     * of RetryEngine on the turn's ledger ([PostLimits.retry]). The credentials [ctx] supplies are written onto the
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
        var body = ctx.observers.perf?.timed(splice.core.perf.PerfKeys.OUTBOUND_PREP_MS) {
            prepared.forClient(this, ctx.observers)
        } ?: prepared.forClient(this, ctx.observers)
        // The size on the turn's perf row is the length forClient just wrote, the JSON before any zstd, so reading it
        // walks nothing. An amended resend keeps this first stamp.
        ctx.observers.perf?.setCount(splice.core.perf.PerfKeys.UPSTREAM_REQ_BYTES, body.body.byteSize())
        // A post with no turn around it keeps a ledger of its own and waits on this client's pacing, telling no one.
        val retry = ctx.limits.retry ?: TurnRetry(
            RetryLedger(),
            TurnWaits { afterMs, _, _ -> retryWaiter.wait(afterMs) },
        )
        val state = RetryState(ctx.limits.rateLimitCooldown ?: cooldown, clock(), retry)
        // RetryEngine ends every turn within its budgets, so the loop runs until a step ends it.
        while (true) {
            when (val step = runAttempt(ctx, body, state, block)) {
                is LoopStep.Done -> return step.result
                is LoopStep.Amend -> {
                    body = prepared.amended(body, step.bodyJson, ctx.observers.perf)
                }
                LoopStep.Continue -> Unit
                LoopStep.TurnWaitExhausted -> return UpstreamPost.TurnWaitExhausted
            }
        }
    }

    /** One loop iteration: the deadline and turn-wait checks, then credentials, the hold they pick, and the attempt.
     *  Each ending is a returned [LoopStep.Done], never a throw. Body preparation remains outside these budgets. */
    private suspend fun <T> runAttempt(
        ctx: PostContext,
        body: RequestBody,
        state: RetryState,
        block: UpstreamHandler<T>,
    ): LoopStep<T> {
        val next = "attempt ${state.ledger.attempts + 1}/$MAX_ATTEMPTS_OUTSIDE_HOLDS"
        if (budget.deadlineExceeded(ctx, state.t0)) {
            ctx.onRetry("upstream retry deadline exceeded (${totalTimeoutMs}ms budget) before $next")
            return steps.gaveUp(ctx, state)
        }
        if (budget.turnWaitExhausted(ctx)) {
            ctx.onRetry("upstream turn wait budget exhausted before $next")
            return if (state.failedYet) steps.gaveUp(ctx, state) else LoopStep.TurnWaitExhausted
        }
        // No hold another turn armed stops this attempt: each turn asks the provider itself (Marcos, Oct 26). The login
        // lookup answers once per attempt: none is the head's NoLogin, and a provider's refusal to grant one ends the
        // call with the refusal's own cause, as its reply would have (LoginAnswer, failure-handling slice 3).
        return when (val answer = ctx.requireAuth()) {
            is LoginAnswer.Granted -> {
                val auth = state.credentials(ctx, answer.credentials, credentialCooldowns, cooldown)
                // What the vendor routes by in the body moves with the place credentials just chose (VendorRoute.body).
                attempt(ctx, state.routedBody(ctx, body, request::body), state, block, auth)
            }
            LoginAnswer.Missing -> LoopStep.Done(UpstreamPost.Ended(UpstreamAuthMissing()))
            is LoginAnswer.Refused -> {
                val evidence = FailureEvidence(LOGIN_REFUSED, said = answer.cause.said?.text)
                LoopStep.Done(UpstreamPost.Ended(UpstreamFailed(answer.cause, evidence, state.ledger.attempts)))
            }
        }
    }

    private suspend fun <T> attempt(
        ctx: PostContext,
        body: RequestBody,
        state: RetryState,
        block: UpstreamHandler<T>,
        auth: AttemptCredentials,
    ): LoopStep<T> {
        ctx.markAttempt()
        val recorder = state.recorderFor(ctx, body, clock)
        var streamHandedOff = false
        // catchCancellable rethrows CancellationException (a cancelled turn aborts cleanly); a failure here is a
        // TRANSPORT error, which the engine reads as a path that did not answer, or a stream torn before the client saw
        // a frame (V4-66: every failure this seam can carry, not only the types the classifier names).
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
            null -> steps.thrown(checkNotNull(transportError), ctx, streamHandedOff, state)
            is RetryOutcome.Done -> readStep(outcome.read, ctx, state)
            is RetryOutcome.Failed -> failedStep(ctx, outcome, body, state)
        }
    }

    /** What the handler made of the stream: a delivered value, a tear the engine may send fresh, or an oversized
     *  frame. */
    private suspend fun <T> readStep(read: StreamRead<T>, ctx: PostContext, state: RetryState): LoopStep<T> =
        when (read) {
            is StreamRead.Read -> LoopStep.Done(UpstreamPost.Delivered(read.value))
            is StreamRead.Torn -> steps.torn(read.cause, ctx, state)
            is StreamRead.Oversized -> LoopStep.Done(UpstreamPost.Ended(read.ending))
        }

    /** A failed answer, as the head's reader read it: the engine says what follows. */
    private suspend fun failedStep(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        body: RequestBody,
        state: RetryState,
    ): LoopStep<Nothing> = steps.failed(ctx, failed, body.json, state)
}

/** The journal's words for a login the provider refused to grant; the line a person reads is the cause's. */
private const val LOGIN_REFUSED = "upstream refused to grant a login"
