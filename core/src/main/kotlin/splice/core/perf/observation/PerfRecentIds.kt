package splice.core.perf.observation

/** FIFO bounded duplicate guard. Live request handles provide permanent terminal idempotence for their own lifetime. */
internal class PerfRecentIds(private val memory: PerfMemoryAccount) {
    private var ids = LinkedHashSet<String>()

    /** A closing handle may transfer its existing reservation; retirement must not need new admission. */
    fun add(id: String, reservedBytes: Long = 0L): Boolean {
        require(reservedBytes == 0L || reservedBytes >= PerfMemoryBudget.ID_BYTES)
        if (id in ids) return false
        if (ids.size >= PerfObservationLimits.MAX_DEDUP) {
            ids.remove(ids.first())
            memory.release(PerfMemoryBudget.ID_BYTES)
        }
        if (reservedBytes == 0L) {
            if (!memory.reserve(PerfMemoryBudget.ID_BYTES)) return false
        } else {
            memory.release(reservedBytes - PerfMemoryBudget.ID_BYTES)
        }
        ids.add(id)
        return true
    }

    fun contains(id: String): Boolean = id in ids

    fun clear() {
        memory.release(ids.size * PerfMemoryBudget.ID_BYTES)
        ids = LinkedHashSet()
    }
}
