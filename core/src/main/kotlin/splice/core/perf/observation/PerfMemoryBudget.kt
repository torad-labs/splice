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
    fun status(): PerfMemoryStatus = PerfMemoryStatus(
        reserved,
        ALLOCATABLE_BYTES,
        OBSERVATION_MEMORY_LIMIT_BYTES,
        snapshotActive,
        OBSERVATION_SNAPSHOT_BYTES,
        HEADROOM_BYTES,
    )

    @Synchronized
    fun open(account: PerfMemoryAccount): Long? {
        collectReleased()
        if (owners.size >= MAX_ACCUMULATOR_OWNERS || reserved + BASE_BYTES > ALLOCATABLE_BYTES) return null
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

    // why: Reserve 16 MiB for observation state within the plan's 32 MiB combined observation/work allowance.
    const val OBSERVATION_MEMORY_LIMIT_BYTES = 16L * 1024 * 1024

    // why: Leave explicit headroom for estimates that cannot account for every JVM/container allocation.
    const val HEADROOM_BYTES = 2L * 1024 * 1024

    // why: One admitted snapshot temporarily copies the aggregate map and creates its immutable result together.
    const val OBSERVATION_SNAPSHOT_BYTES = 4L * 1024 * 1024
    const val ALLOCATABLE_BYTES = OBSERVATION_MEMORY_LIMIT_BYTES - HEADROOM_BYTES - OBSERVATION_SNAPSHOT_BYTES

    // why: Charge histogram buckets, aggregate values and map entries conservatively without measuring JVM allocation.
    const val AGGREGATE_CELL_BYTES = 1536L

    // why: Reserve bounded dimension strings and set/map overhead for each retained family scope.
    const val OBSERVATION_SCOPE_BYTES = 1024L

    // why: Charge each bounded observation/terminal identifier and its deduplication entry.
    const val ID_BYTES = 512L

    // why: Reserve the series key, current value and integration state for each active gauge.
    const val GAUGE_BYTES = 1024L

    // why: Charge a live request/attempt/tool handle until it becomes bounded terminal tombstone state.
    const val HANDLE_BYTES = 1024L

    // why: Empty accumulators still retain maps and bookkeeping that must consume process-wide admission.
    private const val BASE_BYTES = 4096L

    // why: Bound weak-owner accounting itself even when individual accumulators retain almost no observations.
    private const val MAX_ACCUMULATOR_OWNERS = 256
}
