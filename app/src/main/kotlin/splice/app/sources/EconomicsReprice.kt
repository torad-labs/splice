// NEW: Oct 10, 2026 — an hour whose turns had no rate card when they ran is priced again from the perf rows,
// at the cards splice holds NOW (Marlin: "The shipped table prices every turn splice still holds, old ones
// included"). The hourly rollup prices each turn at RECORD time and keeps only the sum (V4-221), so the day
// splice began shipping list prices, every earlier hour would have read as unpriced for the whole retention
// window — and so would every hour before an operator wrote a card of their own.
//
// IT REPRICES ONLY WHAT IT CAN RE-DERIVE. An hour is replaced only when its perf rows carry exactly the turns
// and tokens the rollup counted for it, field for field. The rollup and the perf file are written by different
// paths and sampled on different clocks, so an hour that does not match keeps the figure it recorded: a cost
// re-derived from rows that are not the same turns would be a different hour's answer wearing this one's label.
package splice.app.sources

import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.head.usage.BucketCost
import splice.head.usage.EconomicsBucket
import splice.usage.perf.PerfRow
import kotlin.time.Duration.Companion.hours

private val REPRICE_BUCKET_MS = 1.hours.inWholeMilliseconds

/** Prices an hour again from the perf rows behind it, with the rate cards this daemon holds now. */
internal class EconomicsReprice(private val perf: PerfRowsFileSource, private val price: TurnPrice) {
    /** [buckets] with every re-derivable unpriced hour priced again, or [buckets] unchanged when the rows
     *  cannot be read. The deduction has already run, so [buckets] holds no probe turns. */
    fun priced(buckets: List<EconomicsBucket>): List<EconomicsBucket> {
        val wanted = buckets.filter { it.unpricedTurns > 0L }
        if (wanted.isEmpty()) return buckets
        val evidence = perf.economicsEvidence(wanted.minOf { it.hour })
        if (evidence.work.readError != null || evidence.work.skipped != 0) return buckets
        val probes = evidence.probes.toSet()
        val byHour = evidence.work.rows.filterNot { it in probes }
            .groupBy { it.ts / REPRICE_BUCKET_MS * REPRICE_BUCKET_MS }
        return buckets.map { bucket ->
            if (bucket.unpricedTurns == 0L) bucket else repriced(bucket, byHour[bucket.hour].orEmpty())
        }
    }

    /** The hour's cost from its own rows, or the hour unchanged when the rows are not the turns it counted. */
    private fun repriced(bucket: EconomicsBucket, rows: List<PerfRow>): EconomicsBucket {
        if (!sameTurns(bucket, rows)) return bucket
        var usd = 0.0
        var unpriced = 0L
        for (row in rows) {
            val local = row.fields[PerfKeys.LOCAL_STEP] == 1L
            val amount = price.usd(row.facts.model, row.fields, row.ts)
            if (amount == null) {
                if (!local) unpriced += 1
            } else {
                usd += amount
            }
        }
        // An hour that still holds an unpriced turn keeps its count, so the console says so rather than
        // reading a partial sum as the hour's whole cost.
        return bucket.copy(cost = BucketCost(costUsd = usd, unpricedTurns = unpriced))
    }

    /** Whether [rows] are exactly the turns [bucket] counted: the same turns and the same tokens, each sum
     *  built the way the rollup builds it (every turn's absorbed rounds beside its final round). */
    private fun sameTurns(bucket: EconomicsBucket, rows: List<PerfRow>): Boolean =
        rows.isNotEmpty() && signature(rows) == signature(bucket)

    /** The counts and token sums one hour is recognised by, read off the perf rows. */
    private fun signature(rows: List<PerfRow>): List<Long> {
        val locals = rows.count { it.fields[PerfKeys.LOCAL_STEP] == 1L }.toLong()
        return listOf(
            rows.size - locals,
            locals,
            rows.sumOf { (it.fields[PerfKeys.IN_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).inputTokens },
            rows.sumOf { (it.fields[PerfKeys.CACHED_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).cachedTokens },
            rows.sumOf {
                (it.fields[PerfKeys.CACHE_WRITE_TOKENS] ?: 0) + TurnBill.absorbed(it.fields).cacheWriteTokens
            },
            rows.sumOf { it.fields[PerfKeys.OUT_TOKENS] ?: 0 },
        )
    }

    /** The same six figures the rollup kept for that hour. */
    private fun signature(bucket: EconomicsBucket): List<Long> = listOf(
        bucket.turns,
        bucket.localSteps,
        bucket.inTokens,
        bucket.cachedTokens,
        bucket.cacheWriteTokens,
        bucket.outTokens,
    )
}
