// PORT-OF: splice/spi/UpstreamClient.kt (planRetry, statusPlan, RetryRules, the G5 interlock) @ 3879c4c — invariants unchanged: these DECIDE and never COUNT; not one of the loop's four budgets is mutated here.
//
// WHETHER to retry a failed attempt, and on what terms (HD-25). Was UpstreamClient.planRetry /
// statusPlan / RetryRules and the G5 interlock; only the receiver moved. What a failure IS comes from
// the head's own reader (Replies), read once where the reply arrived; this file decides by that cause.
//
// This file decides; it does not COUNT. Every one of the loop's four budgets (attempt,
// refreshedOnce, streamReissues, amendedOnce) is still mutated inside UpstreamClient and nowhere
// else — these functions read an attempt index and a refresh flag as VALUES and hand back a
// [RetryPlan]. The 2026-07-24 review that found a stray `attempt += 1` eating an amended resend is
// the reason that separation is spelled out rather than assumed.
//
// The clock is not here either: [statusPlan] asks [RateLimitCooldown] to arm its own horizon, and
// the cooldown reads the ElapsedClock UpstreamClient was built with. UpstreamClient stays the
// single DEADLINE authority (t0 and its four checks) on ONE clock base (review 2026-07-22 — this
// and TurnWatchdog/InflightGate must not split-brain).
package splice.upstream.retry

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerfTiming
import splice.core.util.ERR_SNIPPET
import splice.core.wire.HttpStatus
import splice.upstream.ClientFrameEmitted
import splice.upstream.RetryNotice
import splice.upstream.transport.PostContext
import splice.upstream.transport.RetryOutcome
import splice.upstream.transport.TransportFailures
import splice.upstream.transport.UpstreamFailed

internal data class RetryPlan(
    val decision: RetryDecision,
    val refreshedOnce: Boolean,
    val minDelayMs: Long = 0L,
)

internal enum class RetryDecision { RETRY, BACKOFF, GIVE_UP }

internal data class RateLimitTurn(
    val cooldown: RateLimitCooldown,
    val pooledAccount: Boolean,
    /** V4-47: when the provider said its limit lifts, as its reader read it from the reply ("resets at <ISO8601>",
     *  resets_at, resets_in_seconds, a reset header). Arm time is the only point where that fact and the cooldown
     *  are both in scope, and since slice 3 the fact arrives read rather than as a body to parse again. */
    val lift: Lift = Lift.Unstated,
    /** The longest pushback this turn waits out on its login: the 15s ceiling, or a warm session's wait left. */
    val sameLoginWaitMs: Long = RETRY_AFTER_GIVE_UP_MS,
)

internal class RetryRules(private val maxRetries: Int) {
    /** When the limit the loop is planning for lifts, for the cooldown's report of the provider's own reset. Asked
     *  only of the two causes that reach that plan; every other leaf states no reset, and says so here. */
    private fun liftOf(cause: FailureCause.Provider): Lift = when (cause) {
        is FailureCause.Provider.Limited -> cause.lift
        is FailureCause.Provider.NeedsPayment -> cause.lift
        is FailureCause.Provider.Overloaded, is FailureCause.Provider.ServerError,
        is FailureCause.Provider.Forbidden, is FailureCause.Provider.AuthRejected,
        is FailureCause.Provider.TooLong, is FailureCause.Provider.Rejected,
        is FailureCause.Provider.ModelMissing, is FailureCause.Provider.Refused,
        is FailureCause.Provider.Empty, is FailureCause.Provider.ToolTear,
        is FailureCause.Provider.Unrecognized,
        -> Lift.Unstated
    }

    /** The sole failure exit of the retry loop — carries the HTTP status so the classifier's
     *  429/401/5xx floors actually fire (body-text-only classification left them dead code).
     *
     *  V4-61: A 429 CANNOT LEAVE THE LOOP UNARMED. Every 429 used to arm inside rateLimitedPlan
     *  before returning GIVE_UP; now a 429 with budget left plans a BACKOFF instead, and the two
     *  exits where that backoff is refused (turn deadline spent, wait does not fit the remaining
     *  budget) would end the turn with no horizon — and an unarmed exit lets every follower
     *  reproduce the limit upstream. Arming here, at the one exit, makes the invariant structural
     *  rather than a property of each planner branch; re-arming an armed horizon is a max() and
     *  costs nothing. The pushback is the header's own value, clamped by arm() exactly as before,
     *  or the bare-429 default when there was none.
     *
     *  V4-233: a 429 that names a spent PLAN window holds until the reset it named, and arms the
     *  horizon toward it (still clamped). The cause carries that reset and its window (HttpEnding), and
     *  the line a person reads is written from the cause, never from the upstream's words, which stay
     *  whole in the evidence and the log (planRetry's notice). */
    fun giveUp(
        last: RetryOutcome.Failed?,
        cooldown: RateLimitCooldown,
        layers: Int,
        onRetry: RetryNotice,
        refreshed: Boolean = false,
    ): UpstreamFailed {
        if (last == null) return UpstreamFailed(NO_ANSWER, FailureEvidence(NO_ANSWER_MESSAGE), layers)
        if (last.limited) return rateLimitFailure(last, cooldown, layers, onRetry)
        protectFollowers(last, cooldown)
        // A credential this provider named dead (ProviderDeviation.deadCredential) and still refused once a refresh
        // ran is an authentication failure, whatever status it wore: xAI's expiry 403, which its reader reads as a
        // rejected request until the refresh proves the login is the thing refused (V4-38 keeps the unrefreshed one
        // as its reader read it).
        val cause = if (refreshed && last.deadCredential) asRefusedLogin(last) else last.cause
        // V4-117: [layers] is the loop's own attempt count at the moment it gave up — passed IN
        // rather than counted here, because this file decides and never counts (see the header).
        return UpstreamFailed(cause, last.evidence, layers)
    }

    /** The login itself, refused again after its refresh: read as the provider rejecting whose login it is, keeping
     *  the words the provider said. */
    private fun asRefusedLogin(last: RetryOutcome.Failed): FailureCause.Provider =
        FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, last.cause.said)

    /** Whether a refresh could change this answer: splice's own login was refused, or this provider named the
     *  credential dead behind another status. A CLIENT's forwarded login is never refreshed by splice — splice does
     *  not hold it — and its 401 goes back as the client's to fix. */
    private fun loginRefused(failed: RetryOutcome.Failed): Boolean {
        val rejected = failed.cause as? FailureCause.Provider.AuthRejected
        return failed.deadCredential || rejected?.owner == LoginOwner.SPLICE
    }

    /** What the provider's answer left for a log line: its body as it came, bounded. */
    private fun snippet(failed: RetryOutcome.Failed): String = failed.evidence.body.orEmpty().take(ERR_SNIPPET)

    /** Native passthrough replies retain their wire; translated providers retain the plan presentation. */
    internal fun rateLimitFailure(
        last: RetryOutcome.Failed,
        cooldown: RateLimitCooldown,
        layers: Int,
        onRetry: RetryNotice,
    ): UpstreamFailed {
        val reply = last.native
        cooldown.rateLimitReply = reply
        val planned = last.planLimit?.let { cooldown.planHold.hold(it, onRetry) }
        cooldown.arm(planned ?: last.evidence.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
        val limit = last.planLimit.takeIf { reply != null || planned != null }
        return UpstreamFailed(last.cause, last.evidence, layers, planLimit = limit, native = reply)
    }

    /** A long pushback on a failure that is not a limit protects followers only after this observer finishes its
     *  retry budget. Arming before its backoff made the observer's next attempt fail locally with a synthetic 429. */
    private fun protectFollowers(last: RetryOutcome.Failed?, cooldown: RateLimitCooldown) {
        val failed = last ?: return
        val pushback = failed.evidence.retryAfterMs ?: return
        if (pushback > RETRY_AFTER_GIVE_UP_MS && waitingCanHelp(failed.cause)) cooldown.arm(pushback)
    }

    /** Whether waiting can change this answer: the one table below says it, and nothing here asks a second time. */
    private fun waitingCanHelp(cause: FailureCause.Provider): Boolean = refusedAsSent(cause) == null

    suspend fun planRetry(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        refreshedOnce: Boolean,
        rateLimit: RateLimitTurn,
    ): RetryPlan {
        // Grok Build: a reasoning item the provider would not decrypt is refused the same way again, so the retries
        // it used to spin bought nothing (its reader reads it as a rejected request with stale reasoning). The line
        // keeps the words the doctor report's log tail masks the body after (DoctorLogPayloads).
        val stale = (failed.cause as? FailureCause.Provider.Rejected)?.staleReasoning == true
        if (stale) {
            ctx.onRetry(
                "upstream rejected this turn's stale reasoning, an encrypted_content error (no retry): " +
                    snippet(failed),
            )
            return RetryPlan(RetryDecision.GIVE_UP, refreshedOnce)
        }
        val refreshable = loginRefused(failed) &&
            ctx.auth.allowRefreshAfterFailure(failed.deadCredential) &&
            !refreshedOnce
        val perf = ctx.observers.perf
        if (refreshable) perf?.add(PerfKeys.REFRESHES, 1)
        if (refreshable && TurnPerfTiming.timedOr(perf, PerfKeys.REFRESH_MS) { ctx.auth.refresh() } != null) {
            ctx.observers.authRefreshObserver()
            return RetryPlan(RetryDecision.RETRY, refreshedOnce = true)
        }
        ctx.onRetry(
            "upstream ${failed.evidence.status} attempt ${attempt + 1}/$maxRetries: ${snippet(failed)}",
        )
        return statusPlan(
            ctx,
            failed,
            attempt,
            refreshedOnce || refreshable,
            rateLimit,
        )
    }

    /**
     * Why re-sending [failed] cannot change its answer, or null when it can. These are the V4-62
     * CARVE-OUTS — and they are the DEFINITION of that law, not exceptions to it: the operational
     * law is that no turn ends while a retry COULD succeed, and for these the outcome is fixed before
     * the request leaves. Sending it again is not a retry, it is a delay.
     *
     *  - A credential refreshed and REJECTED AGAIN. [nextRefreshed] true means a refresh RAN for this
     *    turn (now or earlier), so every later attempt would carry the identical token. The
     *    escalation ladder for an auth failure is not the same bytes again; it is refresh (G1) → and
     *    when the refresh is itself rejected, EVICT and rotate (pooled — AccountTurnSelectionTest
     *    pins [primary, backup]) or surface (single).
     *  - A CONTEXT OVERFLOW (V4-164): the identical bytes are the identical token count against the
     *    identical window. Its escalation is the CLIENT's — Claude Code compacts on an HTTP 400 "prompt
     *    is too long" that arrives before the answer starts, which the turn head now returns for an
     *    overflow classified before the first model frame (PendingSse) — and every re-send only delays
     *    that. Measured live on the bonsai head at upstreamRetries=10: ten 1.4 MB re-sends, each
     *    re-tokenized by llama-server, 43 s spent re-sending before the turn could end on that 400.
     *  - A CONTENT-POLICY REFUSAL named by the vendor's own code ([policyRefused]): the same bytes
     *    are refused again, and the retry matrix entitles CONTENT_FILTERED to no layer.
     *
     * Same principle as Failure.deterministic, one layer down: that carve-out covers verdicts splice
     * computed with no upstream involved; these, verdicts whose answer cannot change. All of them are
     * what a retry IS, never holes in V4-62.
     */
    private fun fixedVerdict(failed: RetryOutcome.Failed, nextRefreshed: Boolean): String? = when {
        nextRefreshed && loginRefused(failed) ->
            "refused the login again after a refresh (no retry: the bytes would be identical)"
        // V4-233: the upstream named a PLAN window spent until an instant; a re-send before it meets
        // the same answer. A burst 429 carries no such statement and keeps V4-61's 15s schedule.
        failed.planLimit != null ->
            "names its ${failed.planLimit.claim} plan window spent (no retry before the reset it named)"
        else -> refusedAsSent(failed.cause)
    }

    /**
     * Why the same bytes earn the same answer, per leaf (final-design.md section 8), with no `else`: a verdict about
     * the REQUEST is fixed before a re-send leaves, and everything else is waited out. Null means waiting can help.
     * Read once by its reader, decided once here, and the words are the log's.
     *
     * Waited out: a limit (its own plan), the payment V4-73 has always retried until the credits are back, an
     * overload, a server error, an answer that arrived empty, a tool call torn mid-flight that can come back whole,
     * and a failure no reader's table names, which keeps V4-62's budget until slice 4's engine sends it once more
     * (final-design.md section 8: Unrecognized is tried again once, then ends by whose error it is).
     */
    private fun refusedAsSent(cause: FailureCause.Provider): String? = when (cause) {
        is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment, is FailureCause.Provider.Overloaded,
        is FailureCause.Provider.ServerError, is FailureCause.Provider.Empty, is FailureCause.Provider.Unrecognized,
        -> null
        is FailureCause.Provider.TooLong -> "says the request is too long (no retry: the same bytes, the same window)"
        is FailureCause.Provider.ModelMissing -> "does not have the model ${cause.model} (no retry)"
        is FailureCause.Provider.Refused -> "refused this request's content (no retry: the same bytes)"
        is FailureCause.Provider.Rejected -> "rejected this request as sent (no retry: the same bytes)"
        is FailureCause.Provider.Forbidden -> "will not let this login use this model (no retry)"
        // A login splice holds is refreshed first (the gate above); one it does not hold is the client's to renew.
        is FailureCause.Provider.AuthRejected ->
            "refused the login this request sent (no retry: the same login, the same answer)"
                .takeIf { cause.owner == LoginOwner.CLIENT }
        is FailureCause.Provider.ToolTear ->
            "tore this turn's tool call for good (no retry)".takeIf { cause.permanent }
    }

    /** Status/pushback half of the retry decision (split from planRetry: complexity wall). */
    private fun statusPlan(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        nextRefreshed: Boolean,
        rateLimit: RateLimitTurn,
    ): RetryPlan {
        fixedVerdict(failed, nextRefreshed)?.let { why ->
            ctx.onRetry("upstream ${failed.evidence.status} $why")
            return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
        }
        if (failed.limited) {
            val canRetry = attempt < maxRetries - 1
            // V4-47: the reset the provider itself named rides the turn, as its reader read it from the reply's
            // headers and body (Lift), so the cooldown reports it without reading a body of its own.
            return rateLimit.cooldown.rateLimitedPlan(
                failed.evidence.retryAfterMs,
                rateLimit.copy(lift = liftOf(failed.cause)),
                canRetry,
                ctx.onRetry,
                nextRefreshed,
            )
        }
        // V4-62, operator law: "we would retry on any error, no matter what, with different levels
        // of retry and escalation + backoff." So the status GATE is gone from the retry decision —
        // every upstream failure status takes BACKOFF on the shared curve — and `isRetryableStatus`
        // now decides only whether a pushback protects FOLLOWERS (UP-001), which is what it was
        // really about.
        //
        // WHY A 400 EARNS A RETRY. Classification is not reliable enough to refuse a 1.5s one: a
        // 403 has been observed as overload (the mock carries overload_403 for that reason), and
        // the muse 400 on assistant prefill was OUR bug, not the client's — a turn we refused to
        // retry was a turn we broke. The whole default budget on the 200ms doubling curve costs
        // about 1.5s, so a genuinely permanent 4xx is cheap to discover and a misclassified
        // transient is expensive to miss.
        //
        // THE PUSHBACK IS A FLOOR, CLAMPED. A short Retry-After is obeyed exactly. An absurd one
        // still no longer means "go away and never retry" — it is clamped to the interactive
        // ceiling, so the wait is bounded the way the client's patience is. Handing the raw value
        // to the curve would hold a gate slot for the 88 minutes that stacked the 2026-07-19
        // zombie herd; discarding it entirely is what V4-61 fixed for 429 and this generalizes.
        val pushback = failed.evidence.retryAfterMs
        if (pushback != null && pushback > RETRY_AFTER_GIVE_UP_MS) {
            ctx.onRetry(
                "upstream ${failed.evidence.status} Retry-After ${pushback}ms exceeds the interactive budget; " +
                    "waiting ${RETRY_AFTER_GIVE_UP_MS}ms instead",
            )
            // giveUp protects followers if this request cannot recover; its own retry stays eligible.
        }
        val decision = if (attempt == maxRetries - 1) RetryDecision.GIVE_UP else RetryDecision.BACKOFF
        return RetryPlan(
            decision,
            refreshedOnce = nextRefreshed,
            minDelayMs = minOf(pushback ?: 0L, RETRY_AFTER_GIVE_UP_MS),
        )
    }

    // Every surveyed harness (codex, gemini-cli, Claude Code) retries ALL 5xx; 501 stays
    // terminal (Not Implemented never heals) and 4xx stays terminal except 408/429 (G4a).
    fun isRetryableStatus(status: Int): Boolean =
        status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.REQUEST_TIMEOUT ||
            (status in SERVER_ERRORS && status != NOT_IMPLEMENTED)
}

/** The G5 re-issue interlock, as its own receiver: a torn stream is a DIFFERENT question
 *  from a failed status, on a DIFFERENT budget. */
internal class ReissueRules {
    private val transportFailures = TransportFailures()

    /** G5: a torn stream re-issues the request iff it was already handed off (2xx received),
     *  the client has NOT yet seen a byte (FIRST_FRAME unmarked — duplicate-output risk starts
     *  the instant it has), the failure is a retryable transport class, and the small dedicated
     *  budget isn't spent. Separate from the connect-phase `attempt`/`maxRetries` budget on
     *  purpose: re-issuing after a 2xx is a costlier, riskier act than a pre-handoff retry.
     *
     *  HERE and not in TransportFailures.kt: three of its four inputs are LOOP-BUDGET facts, and
     *  it only ever sat beside the cause-chain walk because its one helper did. */
    fun canReissueStream(
        streamHandedOff: Boolean,
        e: Throwable,
        clientFrameEmitted: ClientFrameEmitted,
        streamReissues: Int,
    ): Boolean = streamHandedOff &&
        !clientFrameEmitted() &&
        transportFailures.isRetryableTransport(e) &&
        streamReissues < MAX_STREAM_REISSUES
}

// The G5 budget. Internal because UpstreamClient's two re-issue notices quote it — one number, one
// definition, so a log line can never claim a budget the interlock is not enforcing.
internal const val MAX_STREAM_REISSUES = 2

// The loop gave up with nothing to read: its whole-turn wait was already spent before any attempt went out, so no
// provider answered and no leaf says what it answered. Slice 4's engine ends that turn as splice's own; until then
// the exit keeps today's shape, which is a failure splice cannot name and that is not the request's fault.
private val NO_ANSWER = FailureCause.Provider.Unrecognized(clientError = false, said = null)
private const val NO_ANSWER_MESSAGE = "upstream was never asked: the turn's wait was spent before any attempt"

private const val NOT_IMPLEMENTED = 501

// 599 stays local: no site ANSWERS with it, it is only the top of the 5xx window this file tests
// membership in, so there is no second declaration for HttpStatus to retire.
private const val SERVER_ERROR_MAX = 599

// FILE SCOPE ON PURPOSE: one IntRange for the process: allocate it once, not once per RetryRules.
// Its floor reads the shared HttpStatus member: the bottom of the 5xx window IS 500, so re-typing it
// here was a second declaration of the same status code dressed as a range bound.
private val SERVER_ERRORS = HttpStatus.INTERNAL_SERVER_ERROR..SERVER_ERROR_MAX

// 60s→15s (2026-07-19 storm): a wait the CLIENT would outlive is the client's to make.
// Claude Code abandons + re-sends around 30-60s; a daemon babysitting a >15s pushback
// holds a gate slot for a request nobody is waiting on anymore.
internal const val RETRY_AFTER_GIVE_UP_MS = 15_000L
