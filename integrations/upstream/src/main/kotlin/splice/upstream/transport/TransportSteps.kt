// NEW: the transport-error half of one attempt (a failure the JDK or the engine raised, and a stream torn before the
// client saw a byte), moved verbatim from UpstreamClient.kt, which keeps the loop. The same collaborators the client
// holds are handed in; the state they mutate is still the loop's RetryState, passed per call.
package splice.upstream.transport

import splice.core.util.ERR_SNIPPET
import splice.upstream.retry.MAX_STREAM_REISSUES
import splice.upstream.retry.ReissueRules

internal class TransportSteps(
    private val transportFailures: TransportFailures,
    private val budget: RetryBudget,
    private val pacing: RetryPacing,
    private val totalTimeoutMs: Long,
) {
    private val reissueRules = ReissueRules()

    /** The transport-error half of one attempt, for a failure the JDK or the engine raised. A tear before the client
     *  saw a frame has its own reissue budget ([reissue]); otherwise the two give-up gates retain the existing
     *  pre-handoff transport retry policy, and a failure past them is rethrown as the raw transport error it is. */
    suspend fun failed(
        e: Throwable,
        ctx: PostContext,
        streamHandedOff: Boolean,
        state: RetryState,
        t0: Long,
    ): LoopStep<Nothing> {
        reissue(e, ctx, streamHandedOff, state, t0)?.let { return it }
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
        if (!backoff(e, ctx, state.attempt, t0)) throw e
        state.pendingRetry = if (phase == TransportFailurePhase.POST_SEND) RetryKind.POST_SEND else RetryKind.ORDINARY
        state.attempt += 1
        return LoopStep.Continue
    }

    /** G5: re-issue a stream torn before the client saw a byte, on its own budget. Null means the tear cannot be
     *  re-issued (no budget, not retryable, past the deadline, or no time for the wait), and the caller ends it. */
    suspend fun reissue(
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
        if (!backoff(e, ctx, state.attempt, t0)) return null
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

    /** Both transport paths budget the curve before sleeping; false means it does not fit and nothing slept. */
    private suspend fun backoff(e: Throwable, ctx: PostContext, attempt: Int, t0: Long): Boolean {
        val dns = transportFailures.isDnsFailureTransport(e)
        val plannedDelayMs = pacing.transportDelayMs(attempt, dns)
        if (!budget.backoffFits(ctx, t0, plannedDelayMs)) return false
        ctx.timedBackoff { pacing.pauseAfterTransportError(attempt, dns) }
        return true
    }
}
