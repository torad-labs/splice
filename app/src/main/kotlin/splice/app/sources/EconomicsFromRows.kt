// NEW: Oct 10, 2026 — one reader that turns a set of perf rows into the hour they belong to, used by
// both things that read an hour back from the rows behind it: the repricing (which compares what it
// rebuilds against the hour the rollup recorded, and takes only the cost) and the backfill (which
// takes the whole hour, for hours the rollup has none).
//
// WHY THE TWO SHARE IT. They are the same arithmetic, and the arithmetic is the part that must match
// the live rollup field for field: every turn's absorbed rounds counted beside its final round, local
// steps counted apart from client turns, and a turn whose billing never fully arrived counted rather
// than dropped. Written twice, one of them drifts, and the drift reads as a real difference between
// what an hour cost and what its rows say it cost.
package splice.app.sources

import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.head.usage.BucketBytes
import splice.head.usage.BucketCost
import splice.head.usage.BucketTokens
import splice.head.usage.BucketTools
import splice.head.usage.EconomicsBucket
import splice.head.usage.EconomicsTurnCounts
import splice.usage.perf.PerfRow

/** Reads one hour of economics back from the perf rows written inside it. */
internal class EconomicsFromRows(private val price: TurnPrice) {
    /** The hour [at] as its [rows] describe it, priced at the cards this daemon holds now. */
    fun bucket(at: Long, rows: List<PerfRow>): EconomicsBucket {
        val client = rows.filterNot { it.fields[PerfKeys.LOCAL_STEP] == 1L }
        return EconomicsBucket(
            hour = at,
            counts = EconomicsTurnCounts(
                turns = client.size.toLong(),
                localSteps = (rows.size - client.size).toLong(),
                // The same honesty the live rollup keeps: a turn whose billing never fully arrived is
                // counted here and its observed values are summed, so the console says how many turns
                // the hour could not bill rather than quietly reading the sum as complete.
                unreportedUsageTurns = client.count { !TurnBill.fullyReported(it.fields) }.toLong(),
            ),
            // Input bills every round a turn absorbed beside its final one, the three sums the rollup
            // keeps; output has no absorbed half, so it is the plain sum.
            tokens = BucketTokens(
                inTokens = rows.sumOf {
                    (it.fields[PerfKeys.IN_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).inputTokens
                },
                cachedTokens = rows.sumOf {
                    (it.fields[PerfKeys.CACHED_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).cachedTokens
                },
                cacheWriteTokens = rows.sumOf {
                    (it.fields[PerfKeys.CACHE_WRITE_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).cacheWriteTokens
                },
                outTokens = plain(rows, PerfKeys.OUT_TOKENS),
            ),
            bytes = BucketBytes(
                reqBytes = plain(rows, PerfKeys.REQ_BYTES),
                upstreamBytes = plain(rows, PerfKeys.UPSTREAM_REQ_BYTES),
            ),
            tools = BucketTools(
                toolsEager = plain(rows, PerfKeys.TOOLS_EAGER),
                toolsDeferred = plain(rows, PerfKeys.TOOLS_DEFERRED),
                // Only turns that actually reported a partition, so a head that cannot defer averages
                // over zero turns instead of being diluted by turns that never had the choice.
                deferralTurns = client.count { it.fields[PerfKeys.TOOLS_EAGER] != null }.toLong(),
            ),
            // The turns the upstream refused with a named 429. A 429 that ended as a generic upstream
            // failure is indistinguishable from any other upstream failure in a row, so a rebuilt hour
            // can read this counter low; nothing draws it, and every figure that IS drawn is exact.
            rateLimited = client.count { it.outcome == OutcomeTag.RATE_LIMITED.wire }.toLong(),
            cost = cost(client),
        )
    }

    /** Whether two readings of one hour are the same turns: the counts and token sums it is known by.
     *  The rollup and the perf file are written by different paths on different clocks, so an hour
     *  that does not match must keep the figure it recorded — a cost re-derived from rows that are
     *  not the same turns would be a different hour's answer wearing this one's label. */
    fun sameTurns(recorded: EconomicsBucket, rebuilt: EconomicsBucket): Boolean =
        signature(recorded) == signature(rebuilt)

    private fun cost(client: List<PerfRow>): BucketCost {
        var usd = 0.0
        var unpriced = 0L
        for (row in client) {
            val amount = price.usd(row.facts.model, row.fields, row.ts)
            if (amount == null) unpriced += 1 else usd += amount
        }
        // An hour that still holds an unpriced turn keeps its count, so the console says so rather
        // than reading a partial sum as the hour's whole cost.
        return BucketCost(costUsd = usd, unpricedTurns = unpriced)
    }

    private fun plain(rows: List<PerfRow>, key: String): Long = rows.sumOf { it.fields[key] ?: 0L }

    private fun signature(bucket: EconomicsBucket): List<Long> = listOf(
        bucket.turns,
        bucket.localSteps,
        bucket.inTokens,
        bucket.cachedTokens,
        bucket.cacheWriteTokens,
        bucket.outTokens,
    )
}
