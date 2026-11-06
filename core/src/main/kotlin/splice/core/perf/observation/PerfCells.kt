package splice.core.perf.observation

/** Fixed cell count bounds every histogram and sparse minute map, even under hostile cardinality. */
internal class PerfCells {
    private val entries = LinkedHashMap<PerfCellKey, PerfCell>()
    var dropped = 0L
        private set
    var expiredBefore: Long? = null
        private set

    fun add(metric: String, scope: PerfScope, at: Long, value: Double) {
        cell(metric, scope, at)?.add(value)
    }

    fun integrate(series: PerfSeriesKey, from: Long, until: Long, value: Double) {
        val cutoff = minute(until) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.MINUTE_MS
        var start = maxOf(from, cutoff)
        while (start < until) {
            val end = minOf(until, minute(start) + PerfObservationLimits.MINUTE_MS)
            cell(series.metric, series.scope, start)?.integrate(value, end - start)
            start = end
        }
    }

    fun expire(at: Long) {
        val cutoff = minute(at) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.MINUTE_MS
        val removed = entries.keys.removeIf { it.minute < cutoff }
        if (removed) expiredBefore = cutoff
    }

    fun copy(): PerfCells {
        val result = PerfCells()
        entries.forEach { (key, cell) -> result.entries[key] = cell.copy() }
        result.dropped = dropped
        result.expiredBefore = expiredBefore
        return result
    }

    fun points(until: Long): List<PerfAggregatePoint> = entries.map { (key, value) -> value.point(key, until) }

    private fun cell(metric: String, scope: PerfScope, at: Long): PerfCell? {
        val key = PerfCellKey(metric, scope, minute(at))
        val existing = entries[key]
        if (existing != null) return existing
        if (entries.size >= PerfObservationLimits.MAX_CELLS) {
            dropped++
            return null
        }
        val cell = PerfCell(PerfMetricRegistry.definition(metric)?.type == PerfMetricType.HISTOGRAM)
        entries[key] = cell
        return cell
    }

    private fun minute(at: Long): Long = Math.floorDiv(
        at,
        PerfObservationLimits.MINUTE_MS,
    ) * PerfObservationLimits.MINUTE_MS
}
