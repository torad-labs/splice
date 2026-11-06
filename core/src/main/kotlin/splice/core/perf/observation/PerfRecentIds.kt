package splice.core.perf.observation

/** FIFO bounded duplicate guard. Live request handles provide permanent terminal idempotence for their own lifetime. */
internal class PerfRecentIds(private val memory: PerfMemoryAccount) {
    private var ids = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (id in ids) return false
        if (ids.size >= PerfObservationLimits.MAX_DEDUP) {
            ids.remove(ids.first())
            memory.release(PerfMemoryBudget.ID_BYTES)
        }
        if (!memory.reserve(PerfMemoryBudget.ID_BYTES)) return false
        ids.add(id)
        return true
    }

    fun contains(id: String): Boolean = id in ids

    fun clear() {
        memory.release(ids.size * PerfMemoryBudget.ID_BYTES)
        ids = LinkedHashSet()
    }
}
