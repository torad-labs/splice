package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfMetricType
import splice.core.perf.observation.PerfScope

/** Fixed cell count bounds every histogram and sparse minute map, even under hostile cardinality. */
internal class PerfCells(private val memory: PerfMemoryAccount?) {
    private var entries = LinkedHashMap<PerfCellKey, PerfCell>()
    var dropped = 0L
        private set
    var expiredBefore: Long? = null
        private set
    var expiredInputs = 0L
        private set
    private var retentionFrom = Long.MIN_VALUE

    fun add(metric: String, scope: PerfScope, at: Long, value: Double): Boolean {
        val target = cell(metric, scope, at) ?: return false
        target.add(value)
        return true
    }

    fun integrate(series: PerfSeriesKey, from: Long, until: Long, value: Double) {
        val cutoff = minute(
            until,
        ) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.AGGREGATE_MINUTE_MS
        var start = maxOf(from, cutoff)
        while (start < until) {
            val end = minOf(until, minute(start) + PerfObservationLimits.AGGREGATE_MINUTE_MS)
            cell(series.metric, series.scope, start)?.integrate(value, end - start)
            start = end
        }
    }

    fun expire(at: Long) {
        val cutoff = minute(
            at,
        ) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.AGGREGATE_MINUTE_MS
        retentionFrom = maxOf(retentionFrom, cutoff)
        val before = entries.size
        val removed = entries.keys.removeIf { it.minute < cutoff }
        memory?.release((before - entries.size) * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
        if (removed) expiredBefore = cutoff
    }

    fun copy(): PerfCells {
        // The process-wide snapshot permit reserves this temporary map and its returned DTO generation together.
        val result = PerfCells(null)
        entries.forEach { (key, cell) -> result.entries[key] = cell.copy() }
        result.dropped = dropped
        result.expiredBefore = expiredBefore
        result.expiredInputs = expiredInputs
        result.retentionFrom = retentionFrom
        return result
    }

    fun points(until: Long): List<PerfAggregatePoint> = entries.map { (key, value) -> value.point(key, until) }

    fun clear() {
        memory?.release(entries.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
        entries = LinkedHashMap()
    }

    private fun cell(metric: String, scope: PerfScope, at: Long): PerfCell? {
        val key = PerfCellKey(metric, scope, minute(at))
        val existing = entries[key]
        if (existing != null) return existing
        if (!mayAdd(key.minute)) return null
        val cell = PerfCell(PerfMetricRegistry.definition(metric)?.type == PerfMetricType.HISTOGRAM)
        entries[key] = cell
        return cell
    }

    private fun mayAdd(at: Long): Boolean = when {
        at < retentionFrom -> { expiredInputs++; false }
        entries.size >= PerfObservationLimits.MAX_CELLS -> { dropped++; false }
        memory?.reserve(PerfMemoryBudget.AGGREGATE_CELL_BYTES) == false -> { dropped++; false }
        else -> true
    }

    private fun minute(at: Long): Long = Math.floorDiv(
        at,
        PerfObservationLimits.AGGREGATE_MINUTE_MS,
    ) * PerfObservationLimits.AGGREGATE_MINUTE_MS
}
