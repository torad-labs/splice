package splice.usage.perf.query.reduction

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfIdentity
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PointKey

/** Exact counter/histogram folding; coarsen time only, retaining every identity and scope separately. */
internal class PerfEvidenceBins(private val query: PerfQuery) {
    private val span = query.untilMs - query.fromMs
    private var resolution = MINUTE_MS
    private var collapsed = false
    private var values = LinkedHashMap<PointKey, PerfAggregatePoint>()
    var truncated = false
        private set
    val resolutionMs: Long get() = minOf(span, resolution)

    fun points(): Map<PointKey, PerfAggregatePoint> = values

    fun add(identity: PerfIdentity, point: PerfAggregatePoint) {
        var selected = key(identity, point)
        while (selected !in values && values.size >= MAX_REDUCED_POINTS) {
            if (!coarsen()) { truncated = true; return }
            selected = key(identity, point)
        }
        merge(selected, point)
    }

    private fun key(identity: PerfIdentity, point: PerfAggregatePoint): PointKey {
        val start = if (collapsed) query.fromMs else maxOf(query.fromMs, point.fromMs / resolution * resolution)
        return PointKey(identity, point.metric, point.scope, start)
    }

    private fun merge(key: PointKey, point: PerfAggregatePoint) {
        val prior = values[key]
        val end = if (collapsed) query.untilMs else minOf(query.untilMs, (key.fromMs / resolution + 1) * resolution)
        values[key] = point.copy(
            fromMs = key.fromMs,
            untilMs = end,
            count = (prior?.count ?: 0) + point.count,
            sum = (prior?.sum ?: 0.0) + point.sum,
            min = listOfNotNull(prior?.min, point.min).minOrNull(),
            max = listOfNotNull(prior?.max, point.max).maxOrNull(),
            buckets = List(maxOf(prior?.buckets?.size ?: 0, point.buckets.size)) {
                (prior?.buckets?.getOrElse(it) { 0L } ?: 0L) + point.buckets.getOrElse(it) { 0L }
            },
        )
    }

    private fun coarsen(): Boolean {
        if (collapsed) return false
        resolution = minOf(span, resolution * COARSENING_FACTOR)
        collapsed = resolution >= span
        val previous = values
        values = LinkedHashMap()
        val iterator = previous.entries.iterator()
        while (iterator.hasNext()) {
            val (priorKey, point) = iterator.next()
            iterator.remove()
            merge(key(priorKey.identity, point), point)
        }
        return true
    }
}

// Keep the existing 2,048-cell projection bound; an irreducible dimensional product remains explicitly partial.
private const val MAX_REDUCED_POINTS = 2_048

// Begin with minute bins and double their width until the exact selected population fits the same cell bound.
private const val MINUTE_MS = 60_000L
private const val COARSENING_FACTOR = 2L
