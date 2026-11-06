package splice.core.perf.observation

/** Event transitions integrate exactly; unchanged samples do not become counter deltas. */
internal class PerfGaugeBook(private val cells: PerfCells, private val memory: PerfMemoryAccount) {
    private data class Reading(val value: Double, val at: Long)
    private var readings = LinkedHashMap<PerfSeriesKey, Reading>()
    var dropped = 0L
        private set

    fun set(metric: String, scope: PerfScope, value: Double, at: Long): Boolean {
        val key = PerfSeriesKey(metric, scope)
        val previous = readings[key]
        if (previous == null && !reserve()) {
            dropped++
            return false
        }
        if (previous != null) cells.integrate(key, previous.at, at, previous.value)
        readings[key] = Reading(value, at)
        return cells.add(metric, scope, at, value)
    }

    fun change(metric: String, scope: PerfScope, delta: Double, at: Long) {
        val previous = readings[PerfSeriesKey(metric, scope)]?.value ?: 0.0
        set(metric, scope, (previous + delta).coerceAtLeast(0.0), at)
    }

    fun preview(target: PerfCells, until: Long) {
        readings.forEach { (key, reading) -> target.integrate(key, reading.at, until, reading.value) }
    }

    fun clear() {
        memory.release(readings.size * PerfMemoryBudget.GAUGE_BYTES)
        readings = LinkedHashMap()
    }

    private fun reserve(): Boolean = readings.size < PerfObservationLimits.MAX_GAUGES &&
        memory.reserve(PerfMemoryBudget.GAUGE_BYTES)
}
