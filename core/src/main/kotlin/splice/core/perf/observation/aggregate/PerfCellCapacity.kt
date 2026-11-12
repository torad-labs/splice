package splice.core.perf.observation.aggregate

/** Evicts only the oldest completed minute when the live cell budget is full. */
internal class PerfCellCapacity(
    private val entries: LinkedHashMap<PerfCellKey, PerfCell>,
    private val dirtyPages: PerfDirtyPages,
    private val memory: PerfMemoryAccount?,
    private val evictedMinutes: MutableSet<Long>,
) {
    fun evictOldestBefore(at: Long): Long? {
        if (entries.size < PerfObservationLimits.MAX_CELLS || memory == null) return null
        val oldest = entries.keys.asSequence().map(PerfCellKey::minute).distinct()
            .filter { it < at && !dirtyPages.contains(it) }.minOrNull() ?: return null
        evict(oldest)
        return oldest
    }

    private fun evict(minute: Long) {
        val stale = entries.keys.filter { it.minute == minute }
        stale.forEach(entries::remove)
        memory?.release(stale.size * PerfMemoryBudget.AGGREGATE_CELL_BYTES)
        evictedMinutes += minute
    }
}
