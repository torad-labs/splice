package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import java.io.IOException

/** One identity/time bucket; original point boundaries remain visible after coarsening. */
internal class PerfReadBucket(val identity: PerfIdentity) {
    private val points = LinkedHashMap<Pair<String, PerfScope>, PerfAggregatePoint>()
    private var first = Long.MAX_VALUE
    private var until = 0L
    private var observed = 0L
    private var observedEnd = 0L
    var bytes = BUCKET_OVERHEAD + PerfHistorySize.identity(identity)
        private set

    fun additional(point: PerfAggregatePoint): Long =
        if ((point.metric to point.scope) in points) 0L else PerfHistorySize.point(point) + PERF_READ_CELL_OVERHEAD

    fun coverage(snapshot: PerfAggregateSnapshot) {
        first = minOf(first, snapshot.fromMs)
        until = maxOf(until, snapshot.untilMs)
        val end = minOf(snapshot.untilMs, snapshot.fromMs + snapshot.coverage.observedMs)
        observed += (end - maxOf(observedEnd, snapshot.fromMs)).coerceAtLeast(0L)
        observedEnd = maxOf(observedEnd, end)
    }

    fun point(value: PerfAggregatePoint) {
        val key = value.metric to value.scope
        val previous = points[key]
        bytes += additional(value)
        points[key] = if (previous == null) value else merge(previous, value)
    }

    fun absorb(value: PerfReadBucket) {
        first = minOf(first, value.first)
        until = maxOf(until, value.until)
        observed += value.observed
        observedEnd = maxOf(observedEnd, value.observedEnd)
        // Transfer owned working cells; no escaped snapshot exists during coarsening.
        val iterator = value.points.values.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            point(next)
            iterator.remove()
        }
    }

    fun snapshot(): PerfAggregateSnapshot = PerfAggregateSnapshot(
        identity,
        first,
        until,
        points.values.toList(),
        PerfCoverage(first, until, observed),
    )

    fun fromMs(): Long = first

    private fun merge(a: PerfAggregatePoint, b: PerfAggregatePoint): PerfAggregatePoint {
        if (a.buckets.size != b.buckets.size) throw IOException("aggregate histogram schema changed")
        return a.copy(
            fromMs = minOf(a.fromMs, b.fromMs),
            untilMs = maxOf(a.untilMs, b.untilMs),
            count = a.count + b.count,
            sum = a.sum + b.sum,
            min = listOfNotNull(a.min, b.min).minOrNull(),
            max = listOfNotNull(a.max, b.max).maxOrNull(),
            buckets = a.buckets.indices.map { a.buckets[it] + b.buckets[it] },
            last = if (b.untilMs >= a.untilMs) b.last else a.last,
            weightedSum = a.weightedSum + b.weightedSum,
            coveredMs = a.coveredMs + b.coveredMs,
        )
    }
}

// Charge 2 KiB for each identity/time bucket before identity and point ownership.
internal const val BUCKET_OVERHEAD = 2_048L

// Charge 256 bytes for each metric/scope map entry in both bucket insertion and fold admission estimates.
internal const val PERF_READ_CELL_OVERHEAD = 256L
