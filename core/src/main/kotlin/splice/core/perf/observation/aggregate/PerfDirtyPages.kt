package splice.core.perf.observation.aggregate

/** Tracks dirty minute generations and chooses pages in round-robin order. */
internal class PerfDirtyPages {
    private val generations = LinkedHashMap<Long, Long>()
    private var generation = 0L
    private var afterMinute: Long? = null

    fun mark(minute: Long) {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        generations[minute] = generation
    }

    fun count(): Int = generations.size

    fun contains(minute: Long): Boolean = minute in generations

    fun discardEmpty(minute: Long, expected: Long): Boolean {
        if (generations[minute] != expected) return false
        generations.remove(minute)
        return true
    }

    fun nextMinute(): Long? {
        if (generations.isEmpty()) return null
        val after = afterMinute
        val next = generations.keys.firstOrNull { after == null || it > after } ?: generations.keys.first()
        afterMinute = next
        return next
    }

    fun generation(minute: Long): Long? = generations[minute]

    fun expireBefore(cutoff: Long): List<Long> {
        val expired = generations.keys.filter { it < cutoff }
        expired.forEach(generations::remove)
        return expired
    }

    fun acknowledge(minute: Long, expected: Long): Boolean {
        if (generations[minute] != expected) return false
        generations.remove(minute)
        return true
    }

    fun clear() {
        generations.clear()
        afterMinute = null
    }
}
