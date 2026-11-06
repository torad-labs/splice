package splice.core.perf.observation

/** One fixed-size aggregate. No raw samples or identifiers survive into this state. */
internal class PerfCell(private val histogram: Boolean) {
    private var count = 0L
    private var sum = 0.0
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var last: Double? = null
    private var weighted = 0.0
    private var covered = 0L
    private val buckets = if (histogram) LongArray(PerfMetricRegistry.durationBoundsMs().size + 1) else LongArray(0)

    fun add(value: Double) {
        count++
        sum += value
        extrema(value)
        if (histogram) {
            val position = PerfHistogram.bucket(value)
            buckets[position]++
        }
    }

    fun integrate(value: Double, duration: Long) {
        if (duration > 0) {
            weighted += value * duration
            covered += duration
            extrema(value)
        }
    }

    fun copy(): PerfCell {
        val result = PerfCell(histogram)
        result.count = count
        result.sum = sum
        result.minimum = minimum
        result.maximum = maximum
        result.last = last
        result.weighted = weighted
        result.covered = covered
        buckets.copyInto(result.buckets)
        return result
    }

    fun point(key: PerfCellKey, until: Long): PerfAggregatePoint = PerfAggregatePoint(
        key.metric, key.scope, key.minute, minOf(key.minute + PerfObservationLimits.MINUTE_MS, until),
        count, sum, minimum, maximum, buckets.toList(), last, weighted, covered,
    )

    private fun extrema(value: Double) {
        minimum = minimum?.let { minOf(it, value) } ?: value
        maximum = maximum?.let { maxOf(it, value) } ?: value
        last = value
    }
}
