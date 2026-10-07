// NEW: what one turn contributes to the hourly economics rollup, and the counters it is priced by.
package splice.head.usage

import splice.core.model.TurnBill
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory

/** The per-turn facts the rollup consumes. Nullable where a head genuinely may not report the
 *  field: the chat dialect has no tool deferral at all, and `null` must stay distinguishable from
 *  a real zero — "this head cannot defer" and "this head deferred nothing" are different findings. */
public data class TurnEconomics(
    /** V4-221: the upstream model this turn ran, priced at its own card. NO default, for the reason
     *  [cacheWriteTokens] has none: a call site that forgot it would price every turn at nothing. */
    val model: String?,
    val inTokens: Long?,
    val cachedTokens: Long?,
    /** V4-86: this turn's cache-write bucket, from PerfKeys.CACHE_WRITE_TOKENS. NO default on
     *  purpose — a default would let a new call site drop the most expensive bucket on the turn
     *  and still compile, which is exactly how the counter came to die at this seam. */
    val cacheWriteTokens: Long?,
    val outTokens: Long?,
    val reqBytes: Long?,
    val upstreamBytes: Long?,
    val toolsEager: Long?,
    val toolsDeferred: Long?,
    val rateLimited: Boolean = false,
    /** Set only when the code-mode machine answered locally without an upstream post. */
    val localStep: Boolean = false,
    /** The rounds this turn billed before its final one (PerfKeys.ABSORBED_*). Their input is metered
     *  and billed like the final round's, so it joins the hour's input sums and its dollars. NO default,
     *  for the reason [cacheWriteTokens] has none: a call site that forgot it would drop billed input. */
    val absorbed: AbsorbedRounds,
    /** Source requests whose complete bill never arrived. */
    val cutRounds: Long = 0,
) {
    /** The turn's tokens as the perf-row counters [splice.core.model.TurnPrice] prices, written the way the
     *  row is. */
    public fun counters(): Map<String, Long> = TurnBill.counters(
        Usage(
            inputTokens = inTokens ?: 0,
            outputTokens = outTokens ?: 0,
            cachedTokens = cachedTokens ?: 0,
            cacheWriteTokens = cacheWriteTokens ?: 0,
            history = UsageHistory(absorbed = absorbed, cutRounds = cutRounds),
            reported = buildSet {
                if (inTokens != null) add(UsageField.INPUT)
                if (outTokens != null) add(UsageField.OUTPUT)
                if (cachedTokens != null) add(UsageField.CACHED)
                if (cacheWriteTokens != null) add(UsageField.CACHE_WRITE)
            },
        ),
    )
}
