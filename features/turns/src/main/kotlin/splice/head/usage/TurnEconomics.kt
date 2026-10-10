// NEW: what one turn contributes to the hourly economics rollup, and the counters it is priced by.
package splice.head.usage

import splice.core.model.TurnBill
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageOrigin

/** The per-turn facts the rollup consumes. Nullable where a head genuinely may not report the
 *  field: the chat dialect has no tool deferral at all, and `null` must stay distinguishable from
 *  a real zero — "this head cannot defer" and "this head deferred nothing" are different findings. */
public data class TurnEconomics(
    /** V4-221: the upstream model this turn ran, priced at its own card. NO default, for the reason
     *  [TurnTokens.cacheWriteTokens] has none: a call site that forgot it would price every turn at nothing. */
    val model: String?,
    val tokens: TurnTokens,
    val bytes: TurnBytes,
    val tools: TurnTools,
    val rateLimited: Boolean = false,
    /** Set only when the code-mode machine answered locally without an upstream post. */
    val localStep: Boolean = false,
    /** The rounds this turn billed before its final one (PerfKeys.ABSORBED_*). Their input is metered
     *  and billed like the final round's, so it joins the hour's input sums and its dollars. NO default,
     *  for the reason [TurnTokens.cacheWriteTokens] has none: a call site that forgot it would drop billed input. */
    val history: UsageHistory,
) {
    /** Earlier billed requests retain their reader surface. */
    public val absorbed: AbsorbedRounds get() = history.absorbed

    /** Source requests whose complete bill never arrived. */
    public val cutRounds: Long get() = history.cutRounds

    /** The turn's tokens as the perf-row counters [splice.core.model.TurnPrice] prices, written the way the
     *  row is. */
    public fun counters(): Map<String, Long> = TurnBill.counters(
        Usage(
            inputTokens = tokens.inTokens ?: 0,
            outputTokens = tokens.outTokens ?: 0,
            cachedTokens = tokens.cachedTokens ?: 0,
            cacheWriteTokens = tokens.cacheWriteTokens ?: 0,
            origin = UsageOrigin(history = history),
            reported = buildSet {
                if (tokens.inTokens != null) add(UsageField.INPUT)
                if (tokens.outTokens != null) add(UsageField.OUTPUT)
                if (tokens.cachedTokens != null) add(UsageField.CACHED)
                if (tokens.cacheWriteTokens != null) add(UsageField.CACHE_WRITE)
            },
        ),
    )
}

/** The token counters one turn reported. Each is null when the head did not report it, which stays distinct
 *  from a real zero. NO defaults, for the reason [cacheWriteTokens] has none. */
public data class TurnTokens(
    val inTokens: Long?,
    val cachedTokens: Long?,
    /** V4-86: this turn's cache-write bucket, from PerfKeys.CACHE_WRITE_TOKENS. NO default on
     *  purpose — a default would let a new call site drop the most expensive bucket on the turn
     *  and still compile, which is exactly how the counter came to die at this seam. */
    val cacheWriteTokens: Long?,
    val outTokens: Long?,
)

/** The request sizes one turn reported: what the client sent and what the head sent upstream. */
public data class TurnBytes(
    val reqBytes: Long?,
    val upstreamBytes: Long?,
)

/** The tool-surface partition one turn reported. Both are null on a head whose dialect cannot defer. */
public data class TurnTools(
    val toolsEager: Long?,
    val toolsDeferred: Long?,
)
