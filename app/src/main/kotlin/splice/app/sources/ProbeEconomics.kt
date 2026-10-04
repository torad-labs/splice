// NEW: V4-454 — remove proven legacy probe contributions from the hourly view, never from disk.
package splice.app.sources

import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.head.usage.EconomicsBucket
import splice.head.usage.EconomicsTurnCounts
import splice.usage.perf.PerfRow
import kotlin.time.Duration.Companion.hours

private val PROBE_BUCKET_MS = 1.hours.inWholeMilliseconds
private val LOCAL_ECONOMICS_REFUSALS = setOf(
    OutcomeTag.RATE_LIMITED.wire,
    OutcomeTag.PLAN_LIMIT.wire,
    OutcomeTag.ALL_ACCOUNTS_EXHAUSTED.wire,
    OutcomeTag.BUDGET_BLOCKED.wire,
    OutcomeTag.COMPACTION_PREFLIGHT_COMPACTABLE.wire,
    OutcomeTag.COMPACTION_PREFLIGHT_FIRST_EXCHANGE.wire,
    OutcomeTag.COMPACTION_PREFLIGHT_COMPACT_OVERFLOW.wire,
)

/** A deduction must reconcile with the complete retained rollup and its affected hour.
 *  Perf and economics sampled different clocks: a boundary crossing cannot be guessed.
 *  Lost, torn or mismatched evidence makes the view unavailable, never falsely clean.
 *  New probes cannot dispatch, so successfully reconciled legacy deductions are immutable. */
internal class ProbeEconomics(private val perf: PerfRowsFileSource) {
    private var deductions: Map<Long, EconomicsBucket>? = null

    fun withoutProbes(buckets: List<EconomicsBucket>): List<EconomicsBucket> = synchronized(this) {
        if (buckets.isEmpty()) return@synchronized buckets
        val held = deductions ?: read(buckets).also { deductions = it }
        buckets.map { bucket -> held[bucket.hour]?.let { subtract(bucket, it) } ?: bucket }
            .filter { it.turns != 0L || it.localSteps != 0L }
    }

    private fun read(buckets: List<EconomicsBucket>): Map<Long, EconomicsBucket> {
        val evidence = perf.economicsEvidence(buckets.minOf { it.hour })
        check(evidence.work.readError == null && evidence.work.skipped == 0) {
            "legacy probe economics evidence incomplete: ${evidence.work.readError ?: "unreadable rows"}"
        }
        // TurnTelemetry.recordLocalRefusal writes perf, but never EconomicsStore.record.
        val work = evidence.work.rows.filterNot {
            it.outcome in LOCAL_ECONOMICS_REFUSALS && it.fields[PerfKeys.ATTEMPTS] == 0L
        }
        val all = summarize(work + evidence.probes)
        val probes = summarize(evidence.probes)
        reconcile(buckets, all, probes)
        val recorded = buckets.associateBy { it.hour }
        probes.keys.forEach { hour ->
            check(recorded[hour]?.let { signature(listOf(it)) } == all[hour]?.let { signature(listOf(it)) }) {
                "legacy probe economics hour cannot be assigned from its retained evidence"
            }
        }
        return probes
    }

    private fun reconcile(
        buckets: List<EconomicsBucket>,
        all: Map<Long, EconomicsBucket>,
        probes: Map<Long, EconomicsBucket>,
    ) {
        val latestProbe = probes.keys.maxOrNull()
        val matches = if (latestProbe == null) {
            // Perf is recorded before economics: a live tail may be ahead of the sampled rollup.
            signature(buckets).zip(signature(all.values)).all { (recorded, observed) -> recorded <= observed }
        } else {
            // Later live hours cannot authorize or invalidate a deduction from stable legacy hours.
            signature(buckets.filter { it.hour <= latestProbe }) ==
                signature(all.filterKeys { it <= latestProbe }.values)
        }
        check(matches) { "legacy probe economics evidence does not reconcile with the retained rollup" }
    }

    private fun summarize(rows: List<PerfRow>): Map<Long, EconomicsBucket> =
        rows.groupBy { it.ts / PROBE_BUCKET_MS * PROBE_BUCKET_MS }
            .mapValues { (hour, held) -> held.fold(EconomicsBucket(hour)) { bucket, row -> add(bucket, row) } }

    private fun add(bucket: EconomicsBucket, row: PerfRow): EconomicsBucket {
        val local = count(row, PerfKeys.LOCAL_STEP) == 1L
        return bucket.copy(
            counts = bucket.counts.add(local),
            inTokens = bucket.inTokens + count(row, PerfKeys.IN_TOKENS),
            cachedTokens = bucket.cachedTokens + count(row, PerfKeys.CACHED_TOKENS),
            cacheWriteTokens = bucket.cacheWriteTokens + count(row, PerfKeys.CACHE_WRITE_TOKENS),
            outTokens = bucket.outTokens + count(row, PerfKeys.OUT_TOKENS),
            reqBytes = bucket.reqBytes + count(row, PerfKeys.REQ_BYTES),
            upstreamBytes = bucket.upstreamBytes + count(row, PerfKeys.UPSTREAM_REQ_BYTES),
            toolsEager = bucket.toolsEager + count(row, PerfKeys.TOOLS_EAGER),
            toolsDeferred = bucket.toolsDeferred + count(row, PerfKeys.TOOLS_DEFERRED),
            deferralTurns = bucket.deferralTurns + if (!local && row.fields.containsKey(PerfKeys.TOOLS_EAGER)) 1 else 0,
            unpricedTurns = bucket.unpricedTurns + 1,
        )
    }

    private fun count(row: PerfRow, key: String): Long = row.fields[key] ?: 0

    private fun signature(buckets: Collection<EconomicsBucket>): List<Long> = listOf(
        buckets.sumOf { it.turns },
        buckets.sumOf { it.localSteps },
        buckets.sumOf { it.inTokens },
        buckets.sumOf { it.cachedTokens },
        buckets.sumOf { it.cacheWriteTokens },
        buckets.sumOf { it.outTokens },
        buckets.sumOf { it.reqBytes },
        buckets.sumOf { it.upstreamBytes },
        buckets.sumOf { it.toolsEager },
        buckets.sumOf { it.toolsDeferred },
        buckets.sumOf { it.deferralTurns },
    )

    private fun subtract(bucket: EconomicsBucket, probes: EconomicsBucket): EconomicsBucket = bucket.copy(
        counts = EconomicsTurnCounts(remaining(bucket.turns, probes.turns), bucket.localSteps),
        reqBytes = remaining(bucket.reqBytes, probes.reqBytes),
        upstreamBytes = remaining(bucket.upstreamBytes, probes.upstreamBytes),
        toolsEager = remaining(bucket.toolsEager, probes.toolsEager),
        toolsDeferred = remaining(bucket.toolsDeferred, probes.toolsDeferred),
        deferralTurns = remaining(bucket.deferralTurns, probes.deferralTurns),
        // Old unpriced hours have no count to subtract; their cost remains unknown.
        unpricedTurns = if (bucket.costUsd == null && bucket.unpricedTurns == 0L) {
            0
        } else {
            remaining(bucket.unpricedTurns, probes.unpricedTurns)
        },
    )

    private fun remaining(total: Long, probes: Long): Long {
        check(probes in 0..total) { "legacy probe economics evidence exceeds its recorded bucket" }
        return total - probes
    }
}
