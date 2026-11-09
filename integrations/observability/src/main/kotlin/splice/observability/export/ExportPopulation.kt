package splice.observability.export

import splice.core.perf.observation.PerfAggregatePoint

/** Counter/histogram population arithmetic, independent of admission and checkpoint ownership. */
internal object ExportPopulation {
    fun difference(point: PerfAggregatePoint, old: PerfAggregatePoint?): PerfAggregatePoint? {
        val count = point.count - (old?.count ?: 0)
        val sum = point.sum - (old?.sum ?: 0.0)
        val buckets = point.buckets.mapIndexed { i, n -> n - (old?.buckets?.getOrNull(i) ?: 0L) }
        val increasing = count >= 0 && sum >= 0
        val distribution = buckets.isEmpty() || buckets.sum() == count
        val valid = increasing && distribution
        return if (valid && buckets.none { it < 0 }) point.copy(count = count, sum = sum, buckets = buckets) else null
    }

    fun merge(previous: PerfAggregatePoint, delta: PerfAggregatePoint): PerfAggregatePoint = previous.copy(
        fromMs = minOf(previous.fromMs, delta.fromMs),
        untilMs = maxOf(previous.untilMs, delta.untilMs),
        count = previous.count + delta.count,
        sum = previous.sum + delta.sum,
        min = listOfNotNull(previous.min, delta.min).minOrNull(),
        max = listOfNotNull(previous.max, delta.max).maxOrNull(),
        buckets = delta.buckets.mapIndexed { i, n -> n + (previous.buckets.getOrNull(i) ?: 0L) },
    )
}
