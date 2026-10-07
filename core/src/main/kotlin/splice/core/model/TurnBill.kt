// NEW: one turn's perf row as the requests it billed: its final round, and the rounds it absorbed.
package splice.core.model

import splice.core.perf.PerfKeys
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageRequest

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
    private val billingKeys = setOf(
        PerfKeys.IN_TOKENS,
        PerfKeys.OUT_TOKENS,
        PerfKeys.CACHED_TOKENS,
        PerfKeys.CACHE_WRITE_TOKENS,
    )
    private val inputBillingKeys = setOf(PerfKeys.IN_TOKENS, PerfKeys.CACHED_TOKENS, PerfKeys.CACHE_WRITE_TOKENS)
    private val earlierRequestKeys = setOf(PerfKeys.ABSORBED_ROUNDS, PerfKeys.CUT_SOURCE_ROUNDS)

    /** The counters a turn's [usage] writes on its perf row. */
    public fun counters(usage: Usage): Map<String, Long> = buildMap {
        if (usage.history.request == UsageRequest.NONE) put(PerfKeys.NO_REQUEST, 1L)
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

    /** Decode request ownership and retained earlier requests from the same row the stamp wrote. */
    public fun history(row: Map<String, Long>): UsageHistory = UsageHistory(
        absorbed = absorbed(row),
        cutRounds = row[PerfKeys.CUT_SOURCE_ROUNDS] ?: 0L,
        request = if (row[PerfKeys.NO_REQUEST] == 1L) UsageRequest.NONE else UsageRequest.POSTED,
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

    /** Retain explicit no-request turns and posted spend, but not unowned empty rows or local code-mode steps. */
    public fun isCounted(row: Map<String, Long>): Boolean {
        if (row[PerfKeys.LOCAL_STEP] == 1L) return false
        val starts = row[PerfKeys.TRANSPORT_ATTEMPT_STARTS] ?: row[PerfKeys.ATTEMPTS] ?: 0L
        val earlier = earlierRequestKeys.any { row.getOrDefault(it, 0L) > 0L }
        return row[PerfKeys.NO_REQUEST] == 1L || !isEmpty(row) || starts > 0L || earlier
    }

    /** Only an empty, complete no-request bill with no earlier requests has a rate-independent price. */
    private fun noRequestZero(row: Map<String, Long>): Boolean =
        row[PerfKeys.NO_REQUEST] == 1L && fullyReported(row) &&
            earlierRequestKeys.none { row.getOrDefault(it, 0L) > 0L } && isEmpty(row)

    /** A no-request final round needs no token observations; earlier missing bills still make it incomplete. */
    public fun fullyReported(row: Map<String, Long>): Boolean =
        (row[PerfKeys.NO_REQUEST] == 1L || row.keys.containsAll(billingKeys)) &&
            (row[PerfKeys.CUT_SOURCE_ROUNDS] ?: 0L) == 0L

    /** Exact USD for accounted requests; an empty no-request bill requires no rate card. */
    public fun usd(row: Map<String, Long>, rates: ModelRates?, cost: TokenCost = TokenCost()): Double? = when {
        noRequestZero(row) -> 0.0
        rates != null && fullyReported(row) -> lowerBoundUsd(row, rates, cost)
        else -> null
    }

    /** Charge only observed buckets. An inclusive input group must be complete or entirely absent,
     *  or its cache subtraction cannot be priced safely. Missing output contributes no charge.
     *  With nonnegative rates, this never exceeds the cost of the unreported tokens as well. */
    public fun lowerBoundUsd(row: Map<String, Long>, rates: ModelRates?, cost: TokenCost = TokenCost()): Double? {
        val noRequest = noRequestZero(row)
        if (noRequest || rates == null) return if (noRequest) 0.0 else null
        val inputKeys = inputBillingKeys.count(row::containsKey)
        if (inputKeys != 0 && inputKeys != inputBillingKeys.size) return null
        val absorbed = absorbed(row)
        val prefixRates = if (fullyReported(row)) rates else minimumRates(rates)
        val earlier = if (absorbed.rounds > 0) cost.of(absorbedBuckets(absorbed), prefixRates, absorbed.rounds) else 0.0
        val finalRates = if (inputKeys == 0) minimumRates(rates) else rates
        return cost.of(last(row), finalRates) + earlier
    }

    /** Absorbed requests lost their individual sizes, so an inexact bill must not assume a mean tier. */
    private fun minimumRates(rates: ModelRates): ModelRates {
        val tier = rates.longContext ?: return rates
        return ModelRates(
            input = minOf(rates.input, tier.input),
            cacheRead = minOf(rates.cacheRead, tier.cacheRead),
            output = minOf(rates.output, tier.output),
            cacheWrite = minOf(rates.cacheWrite ?: rates.input, tier.cacheWrite ?: tier.input),
        )
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
