package splice.upstream.transport

import splice.core.util.ElapsedClock

/** What is left of a post's time: the whole-turn wait when a turn owns it, else the client's own cross-attempt
 *  budget measured from [UpstreamClient]'s start of the post. */
internal class RetryBudget(private val totalTimeoutMs: Long, private val clock: ElapsedClock) {
    /** Without a turn owner, direct callers retain their legacy cross-attempt elapsed budget. */
    fun deadlineExceeded(ctx: PostContext, t0: Long): Boolean =
        ctx.remainingTurnWait == null && clock() - t0 >= totalTimeoutMs

    fun turnWaitExhausted(ctx: PostContext): Boolean =
        ctx.remainingTurnWait?.invoke()?.coerceAtLeast(0L) == 0L

    /** Whether a backoff of [plannedDelayMs] fits what is left; false is said to the retry notice. */
    fun backoffFits(ctx: PostContext, t0: Long, plannedDelayMs: Long): Boolean {
        val remainingMs = remainingMs(ctx, t0)
        val fits = plannedDelayMs < remainingMs
        if (!fits) {
            ctx.onRetry(
                "upstream backoff up to ${plannedDelayMs}ms does not fit the remaining ${remainingMs}ms budget",
            )
        }
        return fits
    }

    private fun remainingMs(ctx: PostContext, t0: Long): Long =
        ctx.remainingTurnWait?.invoke()?.coerceAtLeast(0L)
            ?: (totalTimeoutMs - (clock() - t0)).coerceAtLeast(0L)
}
