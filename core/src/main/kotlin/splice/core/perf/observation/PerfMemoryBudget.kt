package splice.core.perf.observation

import java.lang.ref.WeakReference

/** Process-wide resource accounting only; this object never owns an active recorder or its observations. */
internal object PerfMemoryBudget {
    private data class Owner(val reference: WeakReference<PerfMemoryAccount>, var bytes: Long)
    private val owners = HashMap<Long, Owner>()
    private var nextId = 0L
    private var reserved = 0L
    private var snapshotActive = false

    @Synchronized
    fun open(account: PerfMemoryAccount): Long? {
        collectReleased()
        if (owners.size >= MAX_OWNERS || reserved + BASE_BYTES > ALLOCATABLE_BYTES) return null
        val id = ++nextId
        owners[id] = Owner(WeakReference(account), BASE_BYTES)
        reserved += BASE_BYTES
        return id
    }

    @Synchronized
    fun reserve(id: Long?, bytes: Long): Boolean {
        val owner = owners[id] ?: return false
        if (reserved + bytes > ALLOCATABLE_BYTES) collectReleased()
        if (reserved + bytes > ALLOCATABLE_BYTES) return false
        owner.bytes += bytes
        reserved += bytes
        return true
    }

    @Synchronized
    fun release(id: Long?, bytes: Long) {
        val owner = owners[id] ?: return
        val released = minOf(bytes, owner.bytes - BASE_BYTES).coerceAtLeast(0)
        owner.bytes -= released
        reserved -= released
    }

    @Synchronized
    fun close(id: Long?) {
        owners.remove(id)?.let { reserved -= it.bytes }
    }

    @Synchronized
    fun beginSnapshot(): Boolean {
        if (snapshotActive) return false
        snapshotActive = true
        return true
    }

    @Synchronized
    fun endSnapshot() {
        snapshotActive = false
    }

    private fun collectReleased() {
        owners.entries.removeIf { (_, owner) ->
            val released = owner.reference.get() == null
            if (released) reserved -= owner.bytes
            released
        }
    }

    const val LIMIT_BYTES = 16L * 1024 * 1024
    const val HEADROOM_BYTES = 2L * 1024 * 1024
    const val SNAPSHOT_BYTES = 4L * 1024 * 1024
    const val ALLOCATABLE_BYTES = LIMIT_BYTES - HEADROOM_BYTES - SNAPSHOT_BYTES
    const val CELL_BYTES = 1536L
    const val SCOPE_BYTES = 1024L
    const val ID_BYTES = 512L
    const val GAUGE_BYTES = 1024L
    const val HANDLE_BYTES = 1024L
    private const val BASE_BYTES = 4096L
    private const val MAX_OWNERS = 256
}
