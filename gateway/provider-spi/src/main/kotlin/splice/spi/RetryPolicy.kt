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
     *  429/401/5xx floors actually fire (body-text-only classification left them dead code). */
    fun giveUp(last: RetryOutcome.Failed?): Nothing =
        throw UpstreamFailed(last?.text.orEmpty(), last?.status)

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
        if (failed.status == RATE_LIMITED) {
            val canRetry = attempt < maxRetries - 1
            return rateLimit.cooldown.rateLimitedPlan(
                failed.retryAfterMs,
                rateLimit,
                canRetry,
                ctx.onRetry,
                nextRefreshed,
            )
        }
        // gRPC-A6-style negative pushback: a server explicitly asking us to wait longer than the
        // interactive budget means "go away", not "hammer me on a curve" — give up honestly. The
        // client owns any wait past 15s (it re-sends on its own backoff; the daemon holding the
        // slot for a minute is what stacked the 2026-07-19 zombie herd).
        val pushback = failed.retryAfterMs
        val retryable = isRetryableStatus(failed.status)
        if (pushback != null && pushback > RETRY_AFTER_GIVE_UP_MS) {
            ctx.onRetry("upstream ${failed.status} Retry-After ${pushback}ms exceeds interactive budget (no retry)")
            // UP-001: retryable 408/5xx pushback still protects followers on this account. Pool
            // selection reads unavailableForMs(), not this fail-fast horizon, so it never switches.
            if (retryable) rateLimit.cooldown.arm(pushback)
            return RetryPlan(RetryDecision.GIVE_UP, nextRefreshed)
        }
        val decision = if (!retryable || attempt == maxRetries - 1) RetryDecision.GIVE_UP else RetryDecision.BACKOFF
        return RetryPlan(decision, refreshedOnce = nextRefreshed, minDelayMs = pushback ?: 0L)
    }

    // Every surveyed harness (codex, gemini-cli, Claude Code) retries ALL 5xx; 501 stays
    // terminal (Not Implemented never heals) and 4xx stays terminal except 408/429 (G4a).
    fun isRetryableStatus(status: Int): Boolean =
        status == RATE_LIMITED || status == REQUEST_TIMEOUT ||
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

private const val REQUEST_TIMEOUT = 408
private const val NOT_IMPLEMENTED = 501

private const val SERVER_ERROR_MIN = 500
private const val SERVER_ERROR_MAX = 599

// FILE SCOPE ON PURPOSE: one IntRange for the process, same reasoning as FailureRules.kt's
// authBodyRe: allocate it once, not once per RetryRules.
private val SERVER_ERRORS = SERVER_ERROR_MIN..SERVER_ERROR_MAX

// 60s→15s (2026-07-19 storm): a wait the CLIENT would outlive is the client's to make.
// Claude Code abandons + re-sends around 30-60s; a daemon babysitting a >15s pushback
// holds a gate slot for a request nobody is waiting on anymore.
internal const val RETRY_AFTER_GIVE_UP_MS = 15_000L
