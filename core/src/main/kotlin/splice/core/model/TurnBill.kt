// NEW: one turn's perf row as the requests it billed: its final round, and the rounds it absorbed.
package splice.core.model

import splice.core.perf.PerfKeys
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField

/**
 * The one mapping between a turn's usage and its perf row's billing counters, both ways. Every
 * pricer reads a row through it (TurnPrice, SessionCost, TeamsEconomics), and the stamp writes one.
 *
 * A row bills its final round as one request: in_tokens includes both cache buckets, so the
 * cache-miss bucket is in_tokens minus the read and the write, floored at 0 for a malformed row. The
 * rounds it absorbed before that ([PerfKeys.ABSORBED_ROUNDS]) are requests of their own, priced
 * together at their mean size. That is exact for one absorbed round and for rounds on one side of a
 * long-context threshold. When they straddle one, the error is at most the tier's premium over the
 * base card on the absorbed buckets alone: no request is dropped or counted twice, only some may
 * price at the neighbouring tier. A row written before the absorbed counters has none and prices
 * exactly as it always did.
 */
public object TurnBill {
    /** The counters a turn's [usage] writes on its perf row. */
    public fun counters(usage: Usage): Map<String, Long> = buildMap {
        if (UsageField.INPUT in usage.reported) put(PerfKeys.IN_TOKENS, usage.inputTokens)
        if (UsageField.OUTPUT in usage.reported) put(PerfKeys.OUT_TOKENS, usage.outputTokens)
        if (UsageField.CACHED in usage.reported) put(PerfKeys.CACHED_TOKENS, usage.cachedTokens)
        if (UsageField.CACHE_WRITE in usage.reported) put(PerfKeys.CACHE_WRITE_TOKENS, usage.cacheWriteTokens)
        val absorbed = usage.absorbed
        if (absorbed.rounds > 0) {
            put(PerfKeys.ABSORBED_ROUNDS, absorbed.rounds)
            put(PerfKeys.ABSORBED_IN_TOKENS, absorbed.inputTokens)
            put(PerfKeys.ABSORBED_CACHED_TOKENS, absorbed.cachedTokens)
            put(PerfKeys.ABSORBED_CACHE_WRITE_TOKENS, absorbed.cacheWriteTokens)
            put(PerfKeys.ABSORBED_OUT_TOKENS, absorbed.outputTokens)
        }
        // The reasoning tokens a Responses model reports are already inside outputTokens, so this counter
        // is reported, never priced: no bucket below reads it, and a row that carries it costs what the
        // same row without it costs. Written only when positive, like the cut rounds above.
        if (usage.reasoningTokens > 0) put(PerfKeys.REASONING_TOKENS, usage.reasoningTokens)
        if (usage.cutRounds > 0) put(PerfKeys.CUT_SOURCE_ROUNDS, usage.cutRounds)
    }

    /** The rounds [row] absorbed before its final one; none on a row that predates the counters. */
    public fun absorbed(row: Map<String, Long>): AbsorbedRounds = AbsorbedRounds(
        rounds = row[PerfKeys.ABSORBED_ROUNDS] ?: 0L,
        inputTokens = row[PerfKeys.ABSORBED_IN_TOKENS] ?: 0L,
        cachedTokens = row[PerfKeys.ABSORBED_CACHED_TOKENS] ?: 0L,
        cacheWriteTokens = row[PerfKeys.ABSORBED_CACHE_WRITE_TOKENS] ?: 0L,
        outputTokens = row[PerfKeys.ABSORBED_OUT_TOKENS] ?: 0L,
    )

    /** The final round's request: the row's buckets less what its absorbed rounds produced. */
    public fun last(row: Map<String, Long>): TokenBuckets {
        val absorbedOut = row[PerfKeys.ABSORBED_OUT_TOKENS] ?: 0L
        return buckets(
            input = row[PerfKeys.IN_TOKENS] ?: 0L,
            cached = row[PerfKeys.CACHED_TOKENS] ?: 0L,
            written = row[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L,
            output = ((row[PerfKeys.OUT_TOKENS] ?: 0L) - absorbedOut).coerceAtLeast(0L),
        )
    }

    /** Every bucket the row billed, final and absorbed rounds together: the row's token totals. */
    public fun total(row: Map<String, Long>): TokenBuckets {
        val last = last(row)
        val absorbed = absorbedBuckets(absorbed(row))
        return TokenBuckets(
            input = last.input + absorbed.input,
            cacheRead = last.cacheRead + absorbed.cacheRead,
            cacheWrite = last.cacheWrite + absorbed.cacheWrite,
            output = last.output + absorbed.output,
        )
    }

    /** Whether [row] billed nothing at all: a local refusal, or a step with no round. */
    public fun isEmpty(row: Map<String, Long>): Boolean = total(row).isEmpty

    /** USD for the reported requests at [rates]; absent input or output makes the price unknown. */
    public fun usd(row: Map<String, Long>, rates: ModelRates, cost: TokenCost = TokenCost()): Double? {
        if (PerfKeys.IN_TOKENS !in row || PerfKeys.OUT_TOKENS !in row) return null
        val absorbed = absorbed(row)
        val earlier = if (absorbed.rounds > 0) cost.of(absorbedBuckets(absorbed), rates, absorbed.rounds) else 0.0
        return cost.of(last(row), rates) + earlier
    }

    private fun absorbedBuckets(absorbed: AbsorbedRounds): TokenBuckets = buckets(
        input = absorbed.inputTokens,
        cached = absorbed.cachedTokens,
        written = absorbed.cacheWriteTokens,
        output = absorbed.outputTokens,
    )

    private fun buckets(input: Long, cached: Long, written: Long, output: Long): TokenBuckets = TokenBuckets(
        input = (input - cached - written).coerceAtLeast(0L),
        cacheRead = cached,
        cacheWrite = written,
        output = output,
    )
}
