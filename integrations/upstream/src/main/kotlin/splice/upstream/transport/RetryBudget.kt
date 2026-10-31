package splice.upstream.transport

import splice.core.util.ElapsedClock

/** What is left of a post's time: the whole-turn wait when a turn owns it, else the client's own cross-attempt
 *  budget measured from [UpstreamClient]'s start of the post. */
internal class RetryBudget(private val totalTimeoutMs: Long, private val clock: ElapsedClock) {
    /** Without a turn owner, direct callers retain their legacy cross-attempt elapsed budget. */
    fun deadlineExceeded(ctx: PostContext, t0: Long): Boolean =
        ctx.limits.remainingTurnWait == null && clock() - t0 >= totalTimeoutMs

    fun turnWaitExhausted(ctx: PostContext): Boolean =
        ctx.limits.remainingTurnWait?.invoke()?.coerceAtLeast(0L) == 0L

    /** What a wait may still take: the turn's wait where a turn owns the post, else the client's own budget, never less
     *  than nothing. RetryEngine ends a turn whose next wait does not fit it. */
    fun leftMs(ctx: PostContext, t0: Long): Long =
        ctx.limits.remainingTurnWait?.invoke()?.coerceAtLeast(0L)
            ?: (totalTimeoutMs - (clock() - t0)).coerceAtLeast(0L)
}
