package splice.core.perf.observation.aggregate

/** Marks retained minutes whose active gauges acquire more preview interval. */
internal class PerfGaugePreview {
    private var through: Long? = null

    fun advance(cells: PerfCells, gauges: PerfGaugeBook, accepted: Long, until: Long) {
        if (accepted == 0L || !gauges.hasReadings()) return
        if (through == until) return
        val from = through ?: gauges.oldestReadingAt() ?: until
        markMinutes(cells, from, until)
        through = until
    }

    fun markLate(cells: PerfCells, from: Long, through: Long) {
        markMinutes(cells, from, through)
    }

    private fun markMinutes(cells: PerfCells, from: Long, through: Long) {
        val minuteSize = PerfObservationLimits.AGGREGATE_MINUTE_MS
        val cutoff = minute(through) -
            (PerfObservationLimits.RETAINED_MINUTES - 1) * minuteSize
        var current = minute(maxOf(from, cutoff))
        val last = minute(through)
        while (current <= last) {
            cells.markDirty(current)
            if (current == last) break
            current += minuteSize
        }
    }

    private fun minute(at: Long): Long = Math.floorDiv(
        at,
        PerfObservationLimits.AGGREGATE_MINUTE_MS,
    ) * PerfObservationLimits.AGGREGATE_MINUTE_MS
}
