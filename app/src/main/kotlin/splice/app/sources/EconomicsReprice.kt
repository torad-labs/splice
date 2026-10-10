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

import splice.core.model.TurnPrice
import splice.head.usage.EconomicsBucket
import splice.usage.perf.PerfRow
import kotlin.time.Duration.Companion.hours

private val REPRICE_BUCKET_MS = 1.hours.inWholeMilliseconds

/** Prices an hour again from the perf rows behind it, with the rate cards this daemon holds now. */
internal class EconomicsReprice(private val perf: PerfRowsFileSource, price: TurnPrice) {
    private val rows = EconomicsFromRows(price)

    // WHICH rows an hour is made of is not answered here. The reconciliation this must agree with and the
    // backfill it grades beside both take it from EconomicsRows, which says why it cannot be two answers;
    // answering it again inline is how it became two. It did: the inline answer counted a local step for
    // the activity side query, which the rollup never records, so every hour that answered one read as a
    // different hour from its own rows and kept the figure it had.
    private val selection = EconomicsRows()

    /** [buckets] with every re-derivable unpriced hour priced again, or [buckets] unchanged when the rows
     *  cannot be read. The deduction has already run, so [buckets] holds no probe turns. */
    fun priced(buckets: List<EconomicsBucket>): List<EconomicsBucket> {
        val wanted = buckets.filter { it.unpricedTurns > 0L }
        if (wanted.isEmpty()) return buckets
        val evidence = perf.economicsEvidence(wanted.minOf { it.hour })
        if (evidence.work.readError != null || evidence.work.skipped != 0) return buckets
        val probes = evidence.probes.toSet()
        val byHour = selection.billed(evidence.work.rows).filterNot { it in probes }
            .groupBy { it.ts / REPRICE_BUCKET_MS * REPRICE_BUCKET_MS }
        return buckets.map { bucket ->
            if (bucket.unpricedTurns == 0L) bucket else repriced(bucket, byHour[bucket.hour].orEmpty())
        }
    }

    /** The hour's cost from its own rows, or the hour unchanged when the rows are not the turns it counted. */
    private fun repriced(bucket: EconomicsBucket, hourRows: List<PerfRow>): EconomicsBucket {
        if (hourRows.isEmpty()) return bucket
        val rebuilt = rows.bucket(bucket.hour, hourRows)
        if (!rows.sameTurns(bucket, rebuilt)) return bucket
        return bucket.copy(cost = rebuilt.cost)
    }
}
