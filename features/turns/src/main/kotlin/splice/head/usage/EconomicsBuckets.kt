// NEW: Oct 10, 2026 — the SHAPE of one hour of economics, split out of EconomicsStore.kt.
//
// These six are the hour itself: what a head spent in it, in tokens, bytes, tool calls, turns and
// dollars. They are read by the console's payloads, by the budget ledger and by the backfill, and
// written by exactly one collaborator, the store beside them. Keeping the shape and the store in one
// file made that file declare seven types, which is what the concentration law calls a god object:
// the store is the thing with a lock, a file lane and a retention rule, and the hour is a value.
//
// V4-221: DOLLARS ARE PRICED PER TURN, AT RECORD TIME, at the card of the model that turn ran. A
// bucket holds a head's hour, and an hour mixes models (a haiku subagent, a compaction model on a
// head pinned to fable), so the console pricing the hour's token sums at the pinned card mispriced
// every turn on another model. Each turn is priced by core's TurnPrice (the budget's arithmetic),
// summed into [EconomicsBucket.costUsd], and a turn with no card counts in
// [EconomicsBucket.unpricedTurns].
package splice.head.usage

import splice.core.model.TurnBill

/** One hour's client turns and code-mode steps, grouped without widening the economics bucket. */
public data class EconomicsTurnCounts(
    val turns: Long = 0,
    val localSteps: Long = 0,
    /** Turns with incomplete billing usage; numeric sums below include only observed values. */
    val unreportedUsageTurns: Long = 0,
) {
    public fun add(localStep: Boolean): EconomicsTurnCounts =
        if (localStep) copy(localSteps = localSteps + 1) else copy(turns = turns + 1)

    public fun record(turn: TurnEconomics): EconomicsTurnCounts {
        val next = add(turn.localStep)
        if (turn.localStep) return next
        val unknown = !TurnBill.fullyReported(turn.counters())
        return if (unknown) next.copy(unreportedUsageTurns = next.unreportedUsageTurns + 1) else next
    }
}

/** One hour of a head's economics. Sums only — ratios are derived by the reader, never stored,
 *  so a bucket stays mergeable and a rounding choice never hardens into the file. */
public data class EconomicsBucket(
    val hour: Long,
    val counts: EconomicsTurnCounts = EconomicsTurnCounts(),
    val tokens: BucketTokens = BucketTokens(),
    val bytes: BucketBytes = BucketBytes(),
    val tools: BucketTools = BucketTools(),
    val rateLimited: Long = 0,
    val cost: BucketCost = BucketCost(),
) {
    val turns: Long get() = counts.turns
    val localSteps: Long get() = counts.localSteps

    /** The flat read surface over the grouped sums: readers keep one name per figure. */
    val inTokens: Long get() = tokens.inTokens
    val cachedTokens: Long get() = tokens.cachedTokens
    val cacheWriteTokens: Long get() = tokens.cacheWriteTokens
    val outTokens: Long get() = tokens.outTokens
    val reqBytes: Long get() = bytes.reqBytes
    val upstreamBytes: Long get() = bytes.upstreamBytes
    val toolsEager: Long get() = tools.toolsEager
    val toolsDeferred: Long get() = tools.toolsDeferred
    val deferralTurns: Long get() = tools.deferralTurns
    val costUsd: Double? get() = cost.costUsd
    val unpricedTurns: Long get() = cost.unpricedTurns
}

/** One hour's token sums. Observed values only; the unreported-usage count beside them records the unknown turns. */
public data class BucketTokens(
    /** Every request's input the hour billed, cache buckets included: each turn's final round and the
     *  rounds it absorbed, the same three sums for [cachedTokens] and [cacheWriteTokens]. */
    val inTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** V4-86: the cache-WRITE half of [inTokens], disjoint from [cachedTokens] (the read half).
     *  Recorded BESIDE input, never out of it, for the same reason [cachedTokens] is: the plan
     *  meters total input and a written block bills in full. It is a separate sum because it
     *  bills at the vendor's cache_write rate, not the input rate. */
    val cacheWriteTokens: Long = 0,
    val outTokens: Long = 0,
)

/** One hour's request sizes: what clients sent and what the head sent upstream. */
public data class BucketBytes(
    val reqBytes: Long = 0,
    val upstreamBytes: Long = 0,
)

/** One hour's tool-surface partition sums. */
public data class BucketTools(
    val toolsEager: Long = 0,
    val toolsDeferred: Long = 0,
    val deferralTurns: Long = 0,
)

/** One hour's dollars and the turns that could not be priced. */
public data class BucketCost(
    /** V4-221: the hour's dollars, each turn priced at its own model's card. NULL for an hour read
     *  from a file written before the field existed — "not priced then", never $0 — and it stays null
     *  if this daemon adds turns to that same hour, because a sum missing the earlier turns would
     *  read as the hour's whole cost. */
    val costUsd: Double? = 0.0,
    /** V4-221: turns whose model had no rate card; their dollars are not in [costUsd]. */
    val unpricedTurns: Long = 0,
)
