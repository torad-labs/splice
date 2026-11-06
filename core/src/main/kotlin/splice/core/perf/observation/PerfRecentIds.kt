package splice.core.perf.observation

/** FIFO bounded duplicate guard. Live request handles provide permanent terminal idempotence for their own lifetime. */
internal class PerfRecentIds {
    private val ids = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > PerfObservationLimits.MAX_DEDUP) {
            val oldest = ids.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    fun contains(id: String): Boolean = id in ids
}
