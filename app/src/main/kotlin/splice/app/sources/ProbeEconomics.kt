// NEW: V4-454 — remove proven legacy probe contributions from the hourly view, never from disk.
package splice.app.sources

import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.head.usage.BucketBytes
import splice.head.usage.BucketTokens
import splice.head.usage.BucketTools
import splice.head.usage.EconomicsBucket
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

/** Why one head's legacy probe deduction cannot be made, each a fixed sentence the console shows for that head. */
internal enum class ProbeGap(val sentence: String) {
    UNREADABLE("Hourly history is not shown because this command's request log could not be read in full."),
    UNRECONCILED("Hourly history is not shown because it does not match this command's request log."),
    SPLIT_HOUR("Hourly history is not shown because an old probe request cannot be placed in one hour."),
    EXCEEDS("Hourly history is not shown because the request log holds more probe requests than the history counted."),
}

/** What [ProbeEconomics] answers: the buckets without the probes, or the one gap that makes this head's view unavailable. */
internal sealed class ProbeDeduction {
    class Done(val buckets: List<EconomicsBucket>) : ProbeDeduction()

    class Unavailable(val gap: ProbeGap) : ProbeDeduction()
}

/** What reading the probe evidence gave: the deduction per hour, or the gap that stops it. */
private sealed class ProbeRead {
    class Deductions(val byHour: Map<Long, EconomicsBucket>) : ProbeRead()

    class Gapped(val gap: ProbeGap) : ProbeRead()
}

/** A deduction must reconcile with the complete retained rollup and its affected hour.
 *  Perf and economics sampled different clocks: a boundary crossing cannot be guessed.
 *  Lost, torn or mismatched evidence makes this head's view unavailable, never falsely clean.
 *  New probes cannot dispatch, so successfully reconciled legacy deductions are immutable. */
internal class ProbeEconomics(private val perf: PerfRowsFileSource) {
    private var deductions: Map<Long, EconomicsBucket>? = null

    fun withoutProbes(buckets: List<EconomicsBucket>): ProbeDeduction = synchronized(this) {
        if (buckets.isEmpty()) return@synchronized ProbeDeduction.Done(buckets)
        val held = deductions ?: when (val read = read(buckets)) {
            is ProbeRead.Gapped -> return@synchronized ProbeDeduction.Unavailable(read.gap)
            is ProbeRead.Deductions -> read.byHour.also { deductions = it }
        }
        val kept = buckets.map { bucket ->
            val probes = held[bucket.hour] ?: return@map bucket
            if (!fits(bucket, probes)) return@synchronized ProbeDeduction.Unavailable(ProbeGap.EXCEEDS)
            subtract(bucket, probes)
        }
        ProbeDeduction.Done(kept.filter { it.turns != 0L || it.localSteps != 0L })
    }

    private fun read(buckets: List<EconomicsBucket>): ProbeRead {
        val evidence = perf.economicsEvidence(buckets.minOf { it.hour })
        if (evidence.work.readError != null || evidence.work.skipped != 0) return ProbeRead.Gapped(ProbeGap.UNREADABLE)
        // TurnTelemetry.recordLocalRefusal writes perf, but never EconomicsStore.record.
        val work = evidence.work.rows.filterNot {
            it.outcome in LOCAL_ECONOMICS_REFUSALS && it.fields[PerfKeys.ATTEMPTS] == 0L
        }
        val all = summarize(work + evidence.probes)
        val probes = summarize(evidence.probes)
        if (!reconciles(buckets, all, probes)) return ProbeRead.Gapped(ProbeGap.UNRECONCILED)
        val recorded = buckets.associateBy { it.hour }
        val split = probes.keys.any { hour ->
            recorded[hour]?.let { signature(listOf(it)) } != all[hour]?.let { signature(listOf(it)) }
        }
        return if (split) ProbeRead.Gapped(ProbeGap.SPLIT_HOUR) else ProbeRead.Deductions(probes)
    }

    private fun reconciles(
        buckets: List<EconomicsBucket>,
        all: Map<Long, EconomicsBucket>,
        probes: Map<Long, EconomicsBucket>,
    ): Boolean {
        val latestProbe = probes.keys.maxOrNull()
        return if (latestProbe == null) {
            // Perf is recorded before economics: a live tail may be ahead of the sampled rollup.
            signature(buckets).zip(signature(all.values)).all { (recorded, observed) -> recorded <= observed }
        } else {
            // Later live hours cannot authorize or invalidate a deduction from stable legacy hours.
            signature(buckets.filter { it.hour <= latestProbe }) ==
                signature(all.filterKeys { it <= latestProbe }.values)
        }
    }

    private fun summarize(rows: List<PerfRow>): Map<Long, EconomicsBucket> =
        rows.groupBy { it.ts / PROBE_BUCKET_MS * PROBE_BUCKET_MS }
            .mapValues { (hour, held) -> held.fold(EconomicsBucket(hour)) { bucket, row -> add(bucket, row) } }

    private fun add(bucket: EconomicsBucket, row: PerfRow): EconomicsBucket {
        val local = count(row, PerfKeys.LOCAL_STEP) == 1L
        // The economics store sums each turn's absorbed rounds beside its final round; so does this.
        val absorbed = TurnBill.absorbed(row.fields)
        return bucket.copy(
            counts = bucket.counts.add(local),
            tokens = BucketTokens(
                inTokens = bucket.inTokens + count(row, PerfKeys.IN_TOKENS) + absorbed.inputTokens,
                cachedTokens = bucket.cachedTokens + count(row, PerfKeys.CACHED_TOKENS) + absorbed.cachedTokens,
                cacheWriteTokens = bucket.cacheWriteTokens + count(row, PerfKeys.CACHE_WRITE_TOKENS) +
                    absorbed.cacheWriteTokens,
                outTokens = bucket.outTokens + count(row, PerfKeys.OUT_TOKENS),
            ),
            bytes = BucketBytes(
                reqBytes = bucket.reqBytes + count(row, PerfKeys.REQ_BYTES),
                upstreamBytes = bucket.upstreamBytes + count(row, PerfKeys.UPSTREAM_REQ_BYTES),
            ),
            tools = BucketTools(
                toolsEager = bucket.toolsEager + count(row, PerfKeys.TOOLS_EAGER),
                toolsDeferred = bucket.toolsDeferred + count(row, PerfKeys.TOOLS_DEFERRED),
                deferralTurns = bucket.deferralTurns +
                    if (!local && row.fields.containsKey(PerfKeys.TOOLS_EAGER)) 1 else 0,
            ),
            cost = bucket.cost.copy(unpricedTurns = bucket.unpricedTurns + 1),
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

    /** True when the probes never exceed what the bucket counted, field by field. */
    private fun fits(bucket: EconomicsBucket, probes: EconomicsBucket): Boolean = listOf(
        bucket.turns to probes.turns,
        bucket.reqBytes to probes.reqBytes,
        bucket.upstreamBytes to probes.upstreamBytes,
        bucket.toolsEager to probes.toolsEager,
        bucket.toolsDeferred to probes.toolsDeferred,
        bucket.deferralTurns to probes.deferralTurns,
    ).all { (total, probe) -> probe in 0..total } &&
        (unpricedHour(bucket) || probes.unpricedTurns in 0..bucket.unpricedTurns)

    // Old unpriced hours have no count to subtract; their cost remains unknown.
    private fun unpricedHour(bucket: EconomicsBucket): Boolean = bucket.costUsd == null && bucket.unpricedTurns == 0L

    private fun subtract(bucket: EconomicsBucket, probes: EconomicsBucket): EconomicsBucket = bucket.copy(
        counts = bucket.counts.copy(turns = bucket.turns - probes.turns),
        bytes = BucketBytes(
            reqBytes = bucket.reqBytes - probes.reqBytes,
            upstreamBytes = bucket.upstreamBytes - probes.upstreamBytes,
        ),
        tools = BucketTools(
            toolsEager = bucket.toolsEager - probes.toolsEager,
            toolsDeferred = bucket.toolsDeferred - probes.toolsDeferred,
            deferralTurns = bucket.deferralTurns - probes.deferralTurns,
        ),
        cost = bucket.cost.copy(
            unpricedTurns = if (unpricedHour(bucket)) 0 else bucket.unpricedTurns - probes.unpricedTurns,
        ),
    )
}
