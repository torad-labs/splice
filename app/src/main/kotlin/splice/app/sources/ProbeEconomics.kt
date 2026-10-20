// NEW: V4-454 — remove proven legacy probe contributions from the hourly view, never from disk.
package splice.app.sources

import splice.core.model.TurnBill
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.head.usage.BucketBytes
import splice.head.usage.BucketTokens
import splice.head.usage.BucketTools
import splice.head.usage.EconomicsBucket
import splice.usage.perf.PerfRow
import kotlin.time.Duration.Companion.hours

private val PROBE_BUCKET_MS = 1.hours.inWholeMilliseconds

// why: the log is appended in time order but its writer lane can land a row seconds late, so an hour is settled only once
// a row two hours later exists; the hours still inside that are judged again at every look.
private val SETTLED_AFTER_MS = 2 * PROBE_BUCKET_MS

/** Why one head's legacy probe deduction cannot be made, each a fixed sentence the console shows for that head. */
internal enum class ProbeGap(val sentence: String) {
    UNREADABLE("Hourly history is not shown because this command's request log could not be read in full."),
    UNRECONCILED("Hourly history is not shown because it does not match this command's request log."),
    SPLIT_HOUR("Hourly history is not shown because an old probe request cannot be placed in one hour."),
    EXCEEDS("Hourly history is not shown because the request log holds more probe requests than the history counted."),
}

/** What [ProbeEconomics] answers: the hours it can show with their probes taken off and the hours it cannot show, or
 *  the one gap that leaves no hour to show. */
internal sealed class ProbeDeduction {
    class Done(val buckets: List<EconomicsBucket>, val unread: List<Long> = emptyList()) : ProbeDeduction()

    class Unavailable(val gap: ProbeGap) : ProbeDeduction()
}

/** Each hour is judged on its own evidence. An hour the request log covers whole, with no torn line in it, whose rollup
 *  agrees with that log, shows its figure with the legacy probes taken off. Any other hour is not read: lost, torn or
 *  mismatched evidence makes that hour unavailable, never falsely clean. The one exception is an hour after the log's
 *  first and after the newest probe the log holds: there is nothing to take off, so the rollup's count stands. A line that
 *  cannot be placed in an hour makes every hour unavailable. New probes cannot dispatch, so a settled hour stays judged
 *  while the log is only appended to and the rollup for that hour is as it was. */
internal class ProbeEconomics(private val perf: PerfRowsFileSource) {
    /** The answers kept across restarts, so a start does not judge every hour again (ProbeJudgments says why). */
    private val kept = ProbeJudgments(perf.files)

    /** The answer for each hour judged, with the rollup it was an answer for. */
    private val judged = HashMap<Long, ProbeJudged>()
    private var judgedStamp: PerfSourceStamp? = null

    init {
        // A kept hour is held as an hour this run judged: the same rules then keep it, drop it when the files are
        // rewritten and it was not read, or judge it again when its rollup is no longer the one it answered.
        kept.load()?.let { saved ->
            judged.putAll(saved.judged)
            judgedStamp = saved.stamp
        }
    }

    // The one answer to "which rows is this hour made of", shared with the backfill that writes
    // hours this reconciliation then grades. EconomicsRows says why it cannot be two answers.
    private val rows = EconomicsRows()

    /** A request log refused for a line that has no place stays refused while its files are only appended to, so it is
     *  not read again (that read took 17 to 45 s on a live command, at every request). */
    private val refusal = PerfRefusal(perf.files)

    fun withoutProbes(buckets: List<EconomicsBucket>): ProbeDeduction = synchronized(this) {
        if (buckets.isEmpty()) return@synchronized ProbeDeduction.Done(buckets)
        val answers = judge(buckets) ?: return@synchronized ProbeDeduction.Unavailable(ProbeGap.UNREADABLE)
        val unread = buckets.filter { answers.getValue(it.hour).gap != null }
        if (unread.size == buckets.size) {
            val why = unread.groupingBy { answers.getValue(it.hour).gap }.eachCount().maxBy { it.value }.key
            return@synchronized ProbeDeduction.Unavailable(checkNotNull(why))
        }
        val shown = (buckets - unread.toSet()).map { bucket ->
            answers.getValue(bucket.hour).probes?.let { subtract(bucket, it) } ?: bucket
        }
        ProbeDeduction.Done(shown.filter { it.turns != 0L || it.localSteps != 0L }, unread.map { it.hour })
    }

    /** An answer for every hour of [buckets], or null when the log cannot be read or holds a line with no place. */
    private fun judge(buckets: List<EconomicsBucket>): Map<Long, ProbeJudged>? {
        val before = perf.files.stamp()
        val was = HashMap(judged)
        // A settled hour that was read stays read, whatever happens to the files: its probes are retired and its rows were
        // judged. An hour that was not read is judged again once the files are rewritten, which may have mended it.
        if (judgedStamp?.let { !perf.files.unchanged(it) } == true) judged.values.removeIf { it.gap != null }
        val signatures = buckets.associate { it.hour to signature(listOf(it)) }
        val fresh = buckets.filter { judged[it.hour]?.signature != signatures[it.hour] }
        val now = if (fresh.isEmpty()) emptyMap() else judgeFresh(fresh, signatures, before)
        if (judged != was) kept.save(judged, judgedStamp)
        return now?.let { answers -> buckets.associate { it.hour to (answers[it.hour] ?: judged.getValue(it.hour)) } }
    }

    private fun judgeFresh(
        fresh: List<EconomicsBucket>,
        signatures: Map<Long, List<Long>>,
        before: PerfSourceStamp?,
    ): Map<Long, ProbeJudged>? {
        val since = fresh.minOf { it.hour }
        if (refusal.stands(since)) return null
        val read = perf.economicsEvidenceSince(since)
        val unplaced = read.work.readError == null && read.probeTears.any { it == null }
        refusal.note(before, since, unplaced)
        if (read.work.readError != null || unplaced) return null
        val log = LogHours(read)
        val now = HashMap<Long, ProbeJudged>()
        for (bucket in fresh) {
            val gap = log.gap(bucket)
            val probes = log.probes[bucket.hour].takeIf { gap == null }
            now[bucket.hour] = ProbeJudged(signatures.getValue(bucket.hour), probes, gap)
            // an hour the log still grows into is judged again at the next look; one settled stays judged
            if (log.settled(bucket.hour)) {
                judged[bucket.hour] = now.getValue(bucket.hour)
            } else {
                judged.remove(bucket.hour)
            }
        }
        judgedStamp = before
        return now
    }

    /** What one read of the log says about each hour: the rows it holds, its probes, where it ends, and the hours that
     *  hold a line that could be a probe and cannot be read. An hour the log does not reach holds more turns in the rollup
     *  than rows here, so grading it finds that without a rule of its own. */
    private inner class LogHours(read: EconomicsPerfEvidence) {
        private val all = summarize(rows.recorded(read))
        val probes = summarize(read.probes)
        private val torn = read.probeTears.filterNotNull().map { it / PROBE_BUCKET_MS * PROBE_BUCKET_MS }.toSet()
        private val newest = read.work.rows.maxOfOrNull { it.ts }

        /** A torn line is not mended by later rows and no new probe is ever written, so an hour that holds either is settled
         *  at once; any other hour waits for the log to move on. */
        fun settled(hour: Long): Boolean =
            hour in torn || hour in probes || newest != null && hour + SETTLED_AFTER_MS <= newest

        // Probes were dispatched only until they were retired, and none is written now. An hour more than one past the newest probe row or
        // probe-like torn line the log holds, and after the one the log begins in, has no probe to take off: the log's only
        // work there is to subtract probes from the rollup, so the rollup's own count stands even when it is ahead of the log,
        // as it is when a hard stop tore log lines the rollup had already counted (live, Oct 11: claudex and claude-splice at
        // 3 AM CT; claude-splice archives hold probes on Oct 1, which held every later hour). A probe on the edge of an hour is
        // counted in the next one by the rollup, so that next hour is kept out too. The hour the log begins in is
        // partial, an older one is not in the log at all, and an hour at or before a probe may hide one, so none is vouched for.
        private val newestProbe = (probes.keys + torn).maxOrNull()
        private val beginsIn = read.work.oldestHeldTs?.let { it / PROBE_BUCKET_MS * PROBE_BUCKET_MS }

        fun gap(bucket: EconomicsBucket): ProbeGap? {
            if (bucket.hour in torn) return ProbeGap.UNREADABLE
            val afterLogBegins = beginsIn != null && bucket.hour > beginsIn
            val noProbeToTake = afterLogBegins && (newestProbe == null || bucket.hour > newestProbe + PROBE_BUCKET_MS)
            return grade(bucket, all[bucket.hour], probes[bucket.hour], noProbeToTake)
        }
    }

    /** Why this hour cannot be shown with its probes off, or null when its rollup and its rows agree. Perf is recorded
     *  before economics, so a live hour may be ahead of the rollup; an hour holding probes must match to the row. */
    private fun grade(
        bucket: EconomicsBucket,
        seen: EconomicsBucket?,
        probes: EconomicsBucket?,
        noProbeToTake: Boolean,
    ): ProbeGap? {
        val recorded = signature(listOf(bucket))
        val observed = signature(listOfNotNull(seen))
        return when {
            probes == null ->
                ProbeGap.UNRECONCILED.takeUnless { noProbeToTake || recorded.zip(observed).all { (r, o) -> r <= o } }
            recorded != observed -> ProbeGap.SPLIT_HOUR
            !fits(bucket, probes) -> ProbeGap.EXCEEDS
            else -> null
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
            // The live rollup counts a failed probe with no usage as failed, so the probes do the same.
            cost = if (OutcomeTags.isFailedWithoutUsage(row.outcome, row.fields)) {
                bucket.cost.copy(failedTurns = bucket.cost.failedTurns?.plus(1))
            } else {
                bucket.cost.copy(unpricedTurns = bucket.unpricedTurns + 1)
            },
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
        (unpricedHour(bucket) || probes.unpricedTurns in 0..bucket.unpricedTurns) &&
        (bucket.cost.failedTurns?.let { (probes.cost.failedTurns ?: 0L) in 0..it } ?: true)

    // Old unpriced hours have no count to subtract; their cost remains unknown.
    private fun unpricedHour(bucket: EconomicsBucket): Boolean = bucket.costUsd == null && bucket.unpricedTurns == 0L

    private fun subtract(bucket: EconomicsBucket, probes: EconomicsBucket): EconomicsBucket = bucket.copy(
        counts = bucket.counts.copy(turns = bucket.turns - probes.turns),
        tokens = BucketTokens(
            inTokens = bucket.inTokens - probes.inTokens,
            cachedTokens = bucket.cachedTokens - probes.cachedTokens,
            cacheWriteTokens = bucket.cacheWriteTokens - probes.cacheWriteTokens,
            outTokens = bucket.outTokens - probes.outTokens,
        ),
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
            failedTurns = bucket.cost.failedTurns?.let { it - (probes.cost.failedTurns ?: 0L) },
        ),
    )
}
