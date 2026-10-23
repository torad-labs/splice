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
import splice.head.usage.AccountOnPlan
import splice.head.usage.BucketCost
import splice.head.usage.EconomicsBucket
import splice.head.usage.NoPlans
import splice.usage.perf.PerfRow
import kotlin.time.Duration.Companion.hours

private val REPRICE_BUCKET_MS = 1.hours.inWholeMilliseconds

/** How far past an hour the newest row must be before no more rows can land in it. */
private val REPRICE_SETTLED_MS = 2.hours.inWholeMilliseconds

/** Prices an hour again from the perf rows behind it, with the rate cards this daemon holds now. */
internal class EconomicsReprice(
    private val perf: PerfRowsFileSource,
    price: TurnPrice,
    plans: AccountOnPlan = NoPlans,
) {
    private val rows = EconomicsFromRows(price, plans)

    // WHICH rows an hour is made of is not answered here. The reconciliation this must agree with and the
    // backfill it grades beside both take it from EconomicsRows, which says why it cannot be two answers;
    // answering it again inline is how it became two. It did: the inline answer counted a local step for
    // the activity side query, which the rollup never records, so every hour that answered one read as a
    // different hour from its own rows and kept the figure it had.
    private val selection = EconomicsRows()

    // An hour no more rows can land in is answered once: its answer is its own cost fields, or none when its rows are
    // not its turns. Asking again at every poll would regroup the whole window for the hours that can never match.
    private val settled = HashMap<Long, RepriceSettled>()

    // The settled hours outlive a restart while the build, the cards and the plan answers they were priced under are
    // the ones in force (RepriceKept says why), with the models and accounts those hours ran on for the key.
    private val kept = RepriceKept(perf.files, RepriceCards(price, plans))
    private val models = HashSet<String>()
    private val accounts = HashSet<String?>()

    init {
        kept.load()?.let { saved ->
            settled.putAll(saved.settled)
            models.addAll(saved.models)
            accounts.addAll(saved.accounts)
        }
    }

    /** [buckets] with every re-derivable hour priced again: an hour with an unpriced turn, and an hour recorded before
     *  the plan split, whose plan and key requests the rows tell apart by account. [buckets] unchanged where the rows
     *  cannot be read. The deduction has already run, so [buckets] holds no probe turns. */
    fun priced(buckets: List<EconomicsBucket>): List<EconomicsBucket> {
        val wanted = buckets.filter { needs(it) && settled[it.hour]?.recorded != recorded(it) }
        val fresh = if (wanted.isEmpty()) emptyMap() else answer(wanted)
        return buckets.map { bucket ->
            val cost = if (bucket.hour in fresh) {
                fresh.getValue(bucket.hour)
            } else {
                settled[bucket.hour]?.takeIf { it.recorded == recorded(bucket) }?.cost
            }
            if (cost != null) bucket.copy(cost = cost) else bucket
        }
    }

    // An hour from before the rollup priced anything stays "not priced then": its rows could price it, but that is no
    // unpriced count to re-derive from, and a cost appearing for it would read as one it always had.
    private fun needs(bucket: EconomicsBucket): Boolean =
        bucket.unpricedTurns > 0L || bucket.cost.planTurns == null && bucket.costUsd != null

    private fun recorded(bucket: EconomicsBucket): List<Long> =
        listOf(
            bucket.turns,
            bucket.localSteps,
            bucket.inTokens,
            bucket.cachedTokens,
            bucket.outTokens,
            bucket.unpricedTurns,
        )

    /** The rows' cost for each of [wanted] they can answer for, null where an hour's rows are not its turns. */
    private fun answer(wanted: List<EconomicsBucket>): Map<Long, BucketCost?> {
        val evidence = exact(wanted.minOf { it.hour }) ?: return emptyMap()
        val probes = evidence.probes.toSet()
        val byHour = selection.billed(evidence.work.rows).filterNot { it in probes }
            .groupBy { it.ts / REPRICE_BUCKET_MS * REPRICE_BUCKET_MS }
        val newest = evidence.work.rows.maxOfOrNull { it.ts } ?: return emptyMap()
        val was = HashMap(settled)
        val answers = wanted.associate { bucket ->
            val cost = repriced(bucket, byHour[bucket.hour].orEmpty())
            // Only an hour the log has moved well past is final; the hour still being written is asked again.
            if (bucket.hour + REPRICE_SETTLED_MS <= newest) {
                settled[bucket.hour] = RepriceSettled(recorded(bucket), cost)
                byHour[bucket.hour].orEmpty().forEach { row ->
                    row.facts.model?.let(models::add)
                    accounts += row.facts.account
                }
            } else {
                settled.remove(bucket.hour)
            }
            bucket.hour to cost
        }
        if (settled != was) kept.save(RepriceKeptHours(settled, models, accounts))
        return answers
    }

    /** The evidence from [from] when the log reads whole. A torn line does not refuse it. The probe check leaves unread
     *  the hours a probe-like tear may belong to, and a torn work row leaves its hour's rows short of its turns, so
     *  [repriced] keeps that hour's own figure. Until Oct 23, 2026 a torn line with no time
     *  refused the whole pass for the run, and each boot read the log again to find it. */
    private fun exact(from: Long): EconomicsPerfEvidence? =
        perf.economicsEvidenceSince(from).takeIf { it.work.readError == null }

    /** The hour's cost from its own rows, or null when the rows are not the turns it counted. */
    private fun repriced(bucket: EconomicsBucket, hourRows: List<PerfRow>): BucketCost? {
        if (hourRows.isEmpty()) return null
        val rebuilt = rows.bucket(bucket.hour, hourRows)
        return rebuilt.cost.takeIf { rows.sameTurns(bucket, rebuilt) }
    }
}
