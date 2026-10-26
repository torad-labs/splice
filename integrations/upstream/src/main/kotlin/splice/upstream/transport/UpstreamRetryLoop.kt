// NEW: the retry loop's own vocabulary: one post's mutable state across attempts, and the step each attempt
// returns. Split from UpstreamClient.kt, which runs the loop; private there, internal here so the loop can read it.
package splice.upstream.transport

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.util.ElapsedClock
import splice.core.wire.HttpStatus
import splice.upstream.retry.CredentialCooldowns
import splice.upstream.retry.RETRY_AFTER_GIVE_UP_MS
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryDecision
import splice.upstream.retry.RetryPlan
import splice.upstream.retry.RetryRules
import splice.upstream.sse.AttemptRecorder

internal enum class RetryKind { ORDINARY, POST_SEND }

/** Mutable loop state threaded through UpstreamClient.runAttempt — extracted (with it) so `post()` stays
 *  under detekt's LongMethod/CyclomaticComplexMethod ceilings (G4d follow-up to bb8553f). [t0] is when the
 *  post began, the start of its deadline. */
internal class RetryState(var cooldown: RateLimitCooldown, val t0: Long, val maxRetries: Int) {
    /** The rules for THIS request's bound. */
    val rules = RetryRules(maxRetries)

    /** The loop's own give-up: the last failure it saw, with the login marked limited. A pooled login's 429 that
     *  waiting cannot fix is handed back, so the account handoff moves the turn to another login before any byte
     *  reaches the client (§11's Oct deviation), for a translated pool as for the native one. */
    fun gaveUp(ctx: PostContext, rules: RetryRules): LoopStep<Nothing> {
        val failure = rules.giveUp(lastErr, cooldown, attempt, ctx.onRetry, refreshedOnce)
        val refused = ctx.pooled && lastErr?.waitCannotHelp() == true
        return LoopStep.Done(if (refused) UpstreamPost.Refused(failure) else UpstreamPost.Ended(failure))
    }

    /** What is left of the turn's one wait on a warm session's login, whatever the turn's earlier posts already spent
     *  of it; 0 for a cold session. */
    fun warmWaitLeftMs(ctx: PostContext): Long = ctx.limits.warmWait?.leftMs() ?: 0L

    /** The longest one 429's pushback is waited out on this login: the 15s ceiling, or a warm session's wait left. */
    fun sameLoginWaitMs(ctx: PostContext): Long = maxOf(warmWaitLeftMs(ctx), RETRY_AFTER_GIVE_UP_MS)

    /** A 429 backoff spends the warm session's wait on its login. */
    fun countWait(ctx: PostContext, failed: RetryOutcome.Failed, plan: RetryPlan) {
        if (failed.status == HttpStatus.TOO_MANY_REQUESTS && plan.decision == RetryDecision.BACKOFF) {
            ctx.limits.warmWait?.spend(plan.minDelayMs)
        }
    }

    var attempt: Int = 0
    var refreshedOnce: Boolean = false
    var lastErr: RetryOutcome.Failed? = null

    // A retry plan counts only when dispatch survives the next attempt's budget, auth and hold checks.
    var pendingRetry: RetryKind? = null

    fun markResend(ctx: PostContext) {
        val retry = pendingRetry ?: return
        pendingRetry = null
        ctx.markRetry()
        if (retry == RetryKind.POST_SEND) ctx.markPostSendRetry()
    }

    // V4-174: every SEND, whichever budget paid for it (a backoff attempt, the refresh's free
    // retry, a G5 reissue, the RC-4 amended resend) — the ordinal the wire observer sees.
    var sent: Int = 0

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

    /** V4-174: one send's recorder, built only when the context carries an observer; [sent]
     *  counts every send whatever budget paid for it. Here, with [report], because both are
     *  bookkeeping on this state and [runAttempt] has no complexity to spare. */
    fun recorderFor(ctx: PostContext, body: RequestBody, clock: ElapsedClock): AttemptRecorder? {
        sent += 1
        return ctx.observers.wire?.let { AttemptRecorder(sent, ctx.url, body.json, body.encoding, clock) }
    }

    /** V4-174: the attempt is reported once, after it ended either way — a thrown transport
     *  failure and a classified HTTP failure are both endings the trace must show. */
    fun report(ctx: PostContext, recorder: AttemptRecorder?, failure: Throwable?) {
        if (recorder != null) ctx.observers.wire?.attempted(recorder.finish(failure))
    }

    /** Captures one attempt's auth once, so its hold identity and wire headers cannot diverge. */
    suspend fun credentials(
        ctx: PostContext,
        cooldowns: CredentialCooldowns,
        fallback: RateLimitCooldown,
    ): AttemptCredentials? {
        val credentials = ctx.requireAuth() ?: return null
        // Preserve the request-preparation origin: header resolution belongs to POST wait, not prior round work.
        val postedAtMs = ctx.observers.perf?.elapsedMs()
        // The vendor routes each attempt, so a retry sent after its route was left rides the new one (VendorRoute).
        val headers = ctx.extraHeaders(credentials) + ctx.recovery.route?.sending().orEmpty()
        val selected = ctx.limits.rateLimitCooldown ?: cooldowns.forHeaders(
            CredentialKey.headers(credentials, headers),
            (credentials as? Credentials.ApiKey)?.header,
        ) ?: fallback
        cooldown = selected
        return AttemptCredentials(credentials, headers, postedAtMs, selected)
    }

    fun amendStep(ctx: PostContext, outcome: RetryOutcome.Failed, bodyJson: String): LoopStep.Amend? {
        if (amendedOnce) return null
        val amended = ctx.recovery.amendBodyOnFailure(outcome.status, outcome.text, bodyJson) ?: return null
        amendedOnce = true
        ctx.onRetry("amending request body after ${outcome.status} and retrying once")
        return LoopStep.Amend(amended)
    }
}

internal sealed class LoopStep<out T> {
    data class Done<T>(val result: UpstreamPost<T>) : LoopStep<T>()
    data class Amend(val bodyJson: String) : LoopStep<Nothing>()
    data object Continue : LoopStep<Nothing>()

    /** The whole-turn wait budget was already gone: no attempt was made and no upstream
     *  response exists to classify. [UpstreamClient.post] lifts this to [UpstreamPost.TurnWaitExhausted]. */
    data object TurnWaitExhausted : LoopStep<Nothing>()
}
