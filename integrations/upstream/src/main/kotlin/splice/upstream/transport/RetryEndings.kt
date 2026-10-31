// NEW: failure-handling slice 4. How a post's retries end once RetryEngine says they do: the failure the post leaves
// with, and the login marked as that failure warrants. Was RetryPolicy's give-up half (RetryRules.giveUp,
// rateLimitFailure and protectFollowers); RetryPolicy's plans are the engine's now, and that file is gone.
package splice.upstream.transport

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.upstream.retry.HOLD_FLOOR_MS
import splice.upstream.retry.LimitLeft

internal class RetryEndings {
    /** The post's ending as the loop reads it: the failure [last] left, or [cause] where the engine named one. */
    fun done(last: RetryOutcome.Failed?, cause: FailureCause?, state: RetryState, ctx: PostContext): LoopStep<Nothing> =
        LoopStep.Done(UpstreamPost.Ended(ended(last, cause, state, ctx)))

    /** The failure a post leaves with: [last], the last answer a send failed with, read as [cause] where the engine
     *  named one (a login refused again after its renewal is the login itself, whatever status it wore). A limit
     *  marks its login limited (RateLimitCooldown.limitLeft); a long pushback on a failure waiting can heal marks it
     *  too, so the turns after this one steer away. With no answer at all, the post was never sent. */
    fun ended(last: RetryOutcome.Failed?, cause: FailureCause?, state: RetryState, ctx: PostContext): UpstreamFailed {
        val failed = answered(last, cause, state, ctx)
        val outlasted = cause as? FailureCause.Splice.HoldOutlasted ?: return failed
        return UpstreamFailed(failed.cause, failed.evidence, failed.layers, failed.planLimit, failed.native, outlasted)
    }

    private fun answered(
        last: RetryOutcome.Failed?,
        cause: FailureCause?,
        state: RetryState,
        ctx: PostContext,
    ): UpstreamFailed {
        val layers = state.ledger.attempts
        if (last == null) return UpstreamFailed(NO_ANSWER, FailureEvidence(NO_ANSWER_MESSAGE), layers)
        if (last.limited) return limitFailure(last, state, ctx)
        protectFollowers(last, state)
        return UpstreamFailed(cause as? FailureCause.Provider ?: last.cause, last.evidence, layers)
    }

    /** Native passthrough replies keep their wire; translated providers keep the plan's presentation. */
    private fun limitFailure(last: RetryOutcome.Failed, state: RetryState, ctx: PostContext): UpstreamFailed {
        val lift = (last.cause as? FailureCause.Provider.Limited)?.lift
            ?: (last.cause as? FailureCause.Provider.NeedsPayment)?.lift
            ?: Lift.Unstated
        val left = LimitLeft(lift, last.evidence.retryAfterMs, last.planLimit, last.native, ctx.pooled)
        val held = state.cooldown.limitLeft(left, ctx.onRetry)
        val limit = last.planLimit.takeIf { last.native != null || held != null }
        return UpstreamFailed(last.cause, last.evidence, state.ledger.attempts, planLimit = limit, native = last.native)
    }

    /** A long pushback on a failure that is not a limit marks the login only once this turn is done with it, and only
     *  where waiting can change the answer: marking it before would have failed the turn's own next send locally. */
    private fun protectFollowers(last: RetryOutcome.Failed, state: RetryState) {
        val pushback = last.evidence.retryAfterMs ?: return
        if (pushback > HOLD_FLOOR_MS && !last.cause.permanent) state.cooldown.arm(pushback)
    }
}

// The loop gave up with nothing to read: its whole-turn wait was already spent before any attempt went out, so no
// provider answered and no leaf says what it answered: a failure splice cannot name, and not the request's fault.
private val NO_ANSWER = FailureCause.Provider.Unrecognized(clientError = false, said = null)
private const val NO_ANSWER_MESSAGE = "upstream was never asked: the turn's wait was spent before any attempt"
