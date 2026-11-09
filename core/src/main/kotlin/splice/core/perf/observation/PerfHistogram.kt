package splice.core.perf.observation

import kotlin.math.ceil

/** Quantiles merge bucket counts first. Overflow is unknown, never a fabricated maximum latency. */
internal object PerfHistogram {
    private val bounds = PerfMetricRegistry.durationBoundsMs()

    internal fun bucket(value: Double): Int {
        val index = bounds.binarySearch(value)
        return if (index >= 0) index else -index - 1
    }

    public fun percentile(points: List<PerfAggregatePoint>, percentile: Double): Double? {
        require(percentile.isFinite() && percentile in 0.0..1.0) { "Percentile must be between zero and one" }
        if (points.any { it.buckets.size != bounds.size + 1 || it.buckets.any { count -> count < 0 } }) return null
        val merged = LongArray(bounds.size + 1)
        points.forEach { point ->
            point.buckets.forEachIndexed { index, count -> merged[index] += count }
        }
        val total = merged.sum()
        if (total == 0L) return null
        val target = ceil(total * percentile).toLong().coerceAtLeast(1)
        var accumulated = 0L
        val index = merged.indexOfFirst { count ->
            accumulated += count
            accumulated >= target
        }
        return bounds.getOrNull(index)
    }
}
