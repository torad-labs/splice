package splice.core.perf.observation.aggregate

/** The accumulator owns state and one serialized snapshot generation; escaped immutable snapshots belong to callers. */
internal class PerfMemoryAccount : AutoCloseable {
    private val id = PerfMemoryBudget.open(this)
    private var closed = false
    var rejected = if (id == null) 1L else 0L
        private set

    fun reserve(bytes: Long): Boolean {
        val accepted = !closed && PerfMemoryBudget.reserve(id, bytes)
        if (!accepted) rejected++
        return accepted
    }

    fun release(bytes: Long) {
        if (!closed) PerfMemoryBudget.release(id, bytes)
    }

    override fun close() {
        if (!closed) {
            closed = true
            PerfMemoryBudget.close(id)
        }
    }
}
