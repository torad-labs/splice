package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfMetricType
import splice.core.perf.observation.PerfScope

/** Fixed cell count bounds every histogram and sparse minute map, even under hostile cardinality. */
internal class PerfCells(private val memory: PerfMemoryAccount?) {
    private val entries = LinkedHashMap<PerfCellKey, PerfCell>()
    val dirtyPages = PerfDirtyPages()
    private val evictedMinutes = LinkedHashSet<Long>()
    private val capacity = PerfCellCapacity(entries, dirtyPages, memory, evictedMinutes)
    var dropped = 0L
        private set
    var expiredBefore: Long? = null
        private set
    var expiredInputs = 0L
        private set
    var expiredDirtyPages = 0L
        private set
    var emptyPages = 0L
        private set
    private var retentionFrom = Long.MIN_VALUE

    fun add(metric: String, scope: PerfScope, at: Long, value: Double): Boolean {
        val minute = minute(at)
        val target = cell(metric, scope, at) ?: run {
            markDirty(minute)
            return false
        }
        target.add(value)
        markDirty(minute)
        return true
    }

    fun integrate(series: PerfSeriesKey, from: Long, until: Long, value: Double) {
        val cutoff = minute(
            until,
        ) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.AGGREGATE_MINUTE_MS
        var start = maxOf(from, cutoff)
        while (start < until) {
            val end = minOf(until, minute(start) + PerfObservationLimits.AGGREGATE_MINUTE_MS)
            val minute = minute(start)
            val target = cell(series.metric, series.scope, start)
            if (target == null) {
                markDirty(minute)
            } else {
                target.integrate(value, end - start)
                markDirty(minute)
            }
            start = end
        }
    }

    fun expire(at: Long) {
        val cutoff = minute(
            at,
        ) - (PerfObservationLimits.RETAINED_MINUTES - 1) * PerfObservationLimits.AGGREGATE_MINUTE_MS
        retentionFrom = maxOf(retentionFrom, cutoff)
        evictedMinutes.removeIf { it < cutoff }
        val expiredDirty = dirtyPages.expireBefore(cutoff)
        if (expiredDirty.isNotEmpty()) {
            expiredDirty.forEach { oldMinute ->
                val stale = entries.keys.filter { it.minute == oldMinute }
                stale.forEach(entries::remove)
                memory?.release(stale.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
            }
            expiredDirtyPages += expiredDirty.size
            expiredBefore = maxOf(expiredBefore ?: Long.MIN_VALUE, cutoff)
            markDirty(minute(at))
        }
        val staleClean = entries.keys.filter { it.minute < cutoff && !dirtyPages.contains(it.minute) }
        if (staleClean.isNotEmpty()) {
            staleClean.forEach(entries::remove)
            memory?.release(staleClean.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
            expiredBefore = cutoff
        }
    }

    fun markDirty(minute: Long) {
        if (minute >= retentionFrom && minute !in evictedMinutes) dirtyPages.mark(minute)
    }

    fun settlePage(minute: Long, generation: Long, durable: Boolean, empty: Boolean = false) {
        if (empty) {
            if (dirtyPages.discardEmpty(minute, generation)) emptyPages++
            return
        }
        if (!durable || !dirtyPages.acknowledge(minute, generation)) return
        if (minute < retentionFrom) {
            val stale = entries.keys.filter { it.minute == minute }
            stale.forEach(entries::remove)
            memory?.release(stale.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
            if (stale.isNotEmpty()) expiredBefore = maxOf(expiredBefore ?: Long.MIN_VALUE, retentionFrom)
        }
    }

    fun copyMinute(minute: Long): PerfCells {
        val result = PerfCells(null)
        entries.forEach { (key, cell) -> if (key.minute == minute) result.entries[key] = cell.copy() }
        result.dropped = dropped
        result.expiredBefore = expiredBefore
        result.expiredInputs = expiredInputs
        result.expiredDirtyPages = expiredDirtyPages
        result.emptyPages = emptyPages
        result.retentionFrom = retentionFrom
        result.evictedMinutes.addAll(evictedMinutes)
        return result
    }

    fun copy(): PerfCells {
        // The process-wide snapshot permit reserves this temporary map and its returned DTO generation together.
        val result = PerfCells(null)
        entries.forEach { (key, cell) -> result.entries[key] = cell.copy() }
        result.dropped = dropped
        result.expiredBefore = expiredBefore
        result.expiredInputs = expiredInputs
        result.expiredDirtyPages = expiredDirtyPages
        result.emptyPages = emptyPages
        result.retentionFrom = retentionFrom
        result.evictedMinutes.addAll(evictedMinutes)
        return result
    }

    fun points(until: Long): List<PerfAggregatePoint> = entries.map { (key, value) -> value.point(key, until) }

    fun clear() {
        memory?.release(entries.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
        entries.clear()
        dirtyPages.clear()
        evictedMinutes.clear()
        expiredDirtyPages = 0L
        emptyPages = 0L
    }

    private fun cell(metric: String, scope: PerfScope, at: Long): PerfCell? {
        val key = PerfCellKey(metric, scope, minute(at))
        return entries[key] ?: createCell(key)
    }

    private fun createCell(key: PerfCellKey): PerfCell? {
        if (key.minute < retentionFrom || key.minute in evictedMinutes) {
            expiredInputs++
            return null
        }
        if (!mayAdd(key.minute)) return null
        return PerfCell(PerfMetricRegistry.definition(key.metric)?.type == PerfMetricType.HISTOGRAM)
            .also { entries[key] = it }
    }

    private fun mayAdd(at: Long): Boolean {
        if (at < retentionFrom) {
            expiredInputs++
            return false
        }
        capacity.evictOldestBefore(at)?.let { evicted ->
            expiredBefore = maxOf(
                expiredBefore ?: Long.MIN_VALUE,
                evicted + PerfObservationLimits.AGGREGATE_MINUTE_MS,
            )
        }
        return when {
            entries.size >= PerfObservationLimits.MAX_CELLS -> { dropped++; false }
            memory?.reserve(PerfMemoryBudget.AGGREGATE_CELL_BYTES) == false -> { dropped++; false }
            else -> true
        }
    }

    private fun minute(at: Long): Long = Math.floorDiv(
        at,
        PerfObservationLimits.AGGREGATE_MINUTE_MS,
    ) * PerfObservationLimits.AGGREGATE_MINUTE_MS
}
