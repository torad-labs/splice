// PORT-OF: splice/spi/UpstreamClient.kt (planRetry, statusPlan, RetryRules, the G5 interlock) @ 3879c4c — invariants unchanged: these DECIDE and never COUNT; not one of the loop's four budgets is mutated here.
//
// WHETHER to retry a failed attempt, and on what terms (HD-25). Was UpstreamClient.planRetry /
// statusPlan / RetryRules and the G5 interlock; only the receiver moved. The failure PREDICATES
// they consult are FailureRules.kt, which is separate because ResponsesProvider shares one of them.
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
package splice.spi

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerfTiming
import splice.core.wire.HttpStatus

internal data class RetryPlan(
    val decision: RetryDecision,
    val refreshedOnce: Boolean,
    val minDelayMs: Long = 0L,
)

internal enum class RetryDecision { RETRY, BACKOFF, GIVE_UP }

internal data class RateLimitTurn(
    val cooldown: RateLimitCooldown,
    val pooledAccount: Boolean,
)

internal class RetryRules(private val maxRetries: Int) {
    private val failureRules = FailureRules()

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
     *  or the bare-429 default when there was none. */
    fun giveUp(last: RetryOutcome.Failed?, cooldown: RateLimitCooldown, layers: Int): Nothing {
        if (last?.status == HttpStatus.TOO_MANY_REQUESTS) {
            cooldown.arm(last.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
        }
        // V4-117: [layers] is the loop's own attempt count at the moment it gave up — passed IN
        // rather than counted here, because this file decides and never counts (see the header).
        throw UpstreamFailed(last?.text.orEmpty(), last?.status, layers)
    }

    suspend fun planRetry(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        refreshedOnce: Boolean,
        rateLimit: RateLimitTurn,
    ): RetryPlan {
        // Grok Build: encrypted_content decrypt failures must not spin retries.
        if (failureRules.isEncryptedContentError(failed.status, failed.text)) {
            ctx.onRetry(
                "upstream ${failed.status} encrypted_content error (no retry): " +
                    failed.text.take(ERR_SNIPPET),
            )
            return RetryPlan(RetryDecision.GIVE_UP, refreshedOnce)
        }
        val refreshable =
            failureRules.isAuthRefreshableFailure(failed.status, failed.text) &&
                ctx.auth.allowRefreshAfterFailure(failed.status, failed.text) &&
                !refreshedOnce
        if (refreshable) ctx.perf?.add(PerfKeys.REFRESHES, 1)
        if (refreshable && TurnPerfTiming.timedOr(ctx.perf, PerfKeys.REFRESH_MS) { ctx.auth.refresh() } != null) {
            ctx.authRefreshObserver()
            return RetryPlan(RetryDecision.RETRY, refreshedOnce = true)
        }
        ctx.onRetry(
            "upstream ${failed.status} attempt ${attempt + 1}/$maxRetries: " +
                failed.text.take(ERR_SNIPPET),
        )
        return statusPlan(
            ctx,
            failed,
            attempt,
            refreshedOnce || refreshable,
            rateLimit,
        )
    }

    /** Status/pushback half of the retry decision (split from planRetry: complexity wall). */
    private fun statusPlan(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        attempt: Int,
        nextRefreshed: Boolean,
        rateLimit: RateLimitTurn,
    ): RetryPlan {
        // V4-62 CARVE-OUT — and it is the DEFINITION of that law, not an exception to it.
        //
        // [nextRefreshed] true means a refresh RAN for this turn (either it happened now or it was
        // already done), so an auth-refreshable failure reaching here is one where the credential
        // was refreshed and REJECTED AGAIN. Every later attempt would carry the identical token:
        // nothing varies between them, so the outcome is fixed before the request leaves. Sending it
        // again is not a retry, it is a delay — and the operational law is that no turn ends while a
        // retry COULD succeed. This one cannot. The escalation ladder for an auth failure is not the
        // same bytes again; it is refresh (G1) → and when the refresh is itself rejected, EVICT and
        // rotate (pooled — AccountTurnSelectionTest pins [primary, backup]) or surface (single).
        //
        // Same principle as Failure.deterministic, one layer down: that carve-out covers verdicts
        // splice computed with no upstream involved; this one a verdict whose answer cannot change.
        // Both are what a retry IS, never holes in V4-62.
        if (nextRefreshed && failureRules.isAuthRefreshableFailure(failed.status, failed.text)) {
            ctx.onRetry(
                "upstream ${failed.status} rejected the credential again after a refresh " +
                    "(no retry: the bytes would be identical)",
            )
            return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
        }
        if (failed.status == HttpStatus.TOO_MANY_REQUESTS) {
            val canRetry = attempt < maxRetries - 1
            return rateLimit.cooldown.rateLimitedPlan(
                failed.retryAfterMs,
                rateLimit,
                canRetry,
                ctx.onRetry,
                nextRefreshed,
                // V4-47: the failure TEXT, because the provider's own reset lives in the 429 body
                // ("resets at <ISO8601>" / resets_at) and a fail-fast turn never reaches upstream to
                // learn it. ARM TIME is the only point where that fact and the cooldown are both in
                // scope, so it is captured here or nowhere.
                body = failed.text,
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
        val pushback = failed.retryAfterMs
        if (pushback != null && pushback > RETRY_AFTER_GIVE_UP_MS) {
            ctx.onRetry(
                "upstream ${failed.status} Retry-After ${pushback}ms exceeds the interactive budget; " +
                    "waiting ${RETRY_AFTER_GIVE_UP_MS}ms instead",
            )
            // UP-001: a retryable 408/5xx pushback still protects followers on this account. Pool
            // selection reads unavailableForMs(), not this horizon, so it never switches.
            if (isRetryableStatus(failed.status)) rateLimit.cooldown.arm(pushback)
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

private const val NOT_IMPLEMENTED = 501

// 599 stays local: no site ANSWERS with it, it is only the top of the 5xx window this file tests
// membership in, so there is no second declaration for HttpStatus to retire.
private const val SERVER_ERROR_MAX = 599

// FILE SCOPE ON PURPOSE: one IntRange for the process, same reasoning as FailureRules.kt's
// authBodyRe: allocate it once, not once per RetryRules. Its floor reads the shared HttpStatus
// member: the bottom of the 5xx window IS 500, so re-typing it here was a second declaration of
// the same status code dressed as a range bound.
private val SERVER_ERRORS = HttpStatus.INTERNAL_SERVER_ERROR..SERVER_ERROR_MAX

// 60s→15s (2026-07-19 storm): a wait the CLIENT would outlive is the client's to make.
// Claude Code abandons + re-sends around 30-60s; a daemon babysitting a >15s pushback
// holds a gate slot for a request nobody is waiting on anymore.
internal const val RETRY_AFTER_GIVE_UP_MS = 15_000L
