package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfScope
import java.io.IOException

/** Streaming hour reduction keeps one minute decode and bounded aggregate cells, never sixty decoded minutes. */
internal class PerfHourFold(private val hour: Long) {
    private val identities = LinkedHashMap<PerfIdentity, HourIdentity>()
    private var bytes = 0L

    fun add(file: PerfHistoryFile) {
        file.snapshots.forEach(::addSnapshot)
    }

    private fun addSnapshot(snapshot: PerfAggregateSnapshot) {
        val state = identities.getOrPut(snapshot.identity) { HourIdentity() }
        state.covered = (state.covered + snapshot.coverage.observedMs).coerceAtMost(PERF_HOUR_MS)
        state.issues.addAll(snapshot.coverage.issues.take(MAX_HOUR_ISSUES - state.issues.size))
        for (point in snapshot.points) {
            val key = point.metric to point.scope
            val previous = state.points[key]
            if (previous == null) {
                bytes += CELL_BYTES + point.metric.length * 2L + point.scope.toString().length * 2L
                if (bytes > MAX_HOUR_BYTES) throw IOException("aggregate hour memory limit")
                state.points[key] = point.copy(fromMs = hour)
            } else {
                state.points[key] = merge(previous, point)
            }
        }
    }

    fun result(): PerfHistoryFile = PerfHistoryFile(
        resolutionMs = PERF_HOUR_MS,
        fromMs = hour,
        snapshots = identities.map { (identity, state) ->
            PerfAggregateSnapshot(
                identity,
                hour,
                hour + PERF_HOUR_MS,
                state.points.values.toList(),
                PerfCoverage(hour, hour + PERF_HOUR_MS, state.covered, state.issues.distinct()),
            )
        },
    )

    private fun merge(a: PerfAggregatePoint, b: PerfAggregatePoint): PerfAggregatePoint = a.copy(
        untilMs = maxOf(a.untilMs, b.untilMs),
        count = a.count + b.count,
        sum = a.sum + b.sum,
        min = listOfNotNull(a.min, b.min).minOrNull(),
        max = listOfNotNull(a.max, b.max).maxOrNull(),
        buckets = List(maxOf(a.buckets.size, b.buckets.size)) {
            a.buckets.getOrElse(it) { 0L } + b.buckets.getOrElse(it) { 0L }
        },
        last = if (b.untilMs >= a.untilMs) b.last else a.last,
        weightedSum = a.weightedSum + b.weightedSum,
        coveredMs = a.coveredMs + b.coveredMs,
    )

    private class HourIdentity {
        val points = LinkedHashMap<Pair<String, PerfScope>, PerfAggregatePoint>()
        val issues = ArrayList<PerfIssue>()
        var covered = 0L
    }
}

private const val MAX_HOUR_BYTES = 2L * 1_024 * 1_024
private const val CELL_BYTES = 2_048L
private const val MAX_HOUR_ISSUES = 64
