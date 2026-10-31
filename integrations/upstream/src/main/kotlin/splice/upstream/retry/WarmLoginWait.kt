// NEW: a warm turn's one budget for waiting out rate limits on its own login (Marcos, Oct 19), spent by admission and
// by every post of the turn.
package splice.upstream.retry

import java.util.concurrent.atomic.AtomicLong

/** What a warm turn may still wait, in all, for rate limits on the login its prompt cache lives on before it moves.
 *  One belongs to one turn's login choice and every wait the turn makes there spends it: admission's wait for a
 *  gateway hold, a post's wait for an armed hold and a post's 429 backoff. Admission and each post once held the full
 *  budget of their own, so a turn could wait several times the limit before it moved. */
public class WarmLoginWait(private val budgetMs: Long) {
    private val spentMs = AtomicLong(0L)

    /** The wait the turn has left; never negative. */
    public fun leftMs(): Long = (budgetMs - spentMs.get()).coerceAtLeast(0L)

    /** Counts [waitedMs] against the budget. */
    public fun spend(waitedMs: Long) {
        spentMs.addAndGet(waitedMs)
    }
}
