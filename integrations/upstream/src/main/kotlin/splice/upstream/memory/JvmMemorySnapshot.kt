// NEW: diagnostic observations are separate from estimated lease charges and collector reclamation.
package splice.upstream.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.memory.HeapRetentionSnapshot

/** Diagnostic-only process observations. Resident memory is not an estimate of native memory. */
@Serializable
public data class JvmMemorySnapshot(
    public val retention: HeapRetentionSnapshot,
    public val heap: JvmMemoryRegion,
    @SerialName("non_heap") public val nonHeap: JvmMemoryRegion,
    public val direct: JvmBufferSnapshot?,
    public val mapped: JvmBufferSnapshot?,
    @SerialName("resident_bytes") public val residentBytes: Long?,
    @SerialName("native_measurement") public val nativeMeasurement: String,
)

/** Values from MemoryMXBean. A negative maximum means the JVM does not expose one. */
@Serializable
public data class JvmMemoryRegion(
    @SerialName("used_bytes") public val usedBytes: Long,
    @SerialName("committed_bytes") public val committedBytes: Long,
    @SerialName("max_bytes") public val maxBytes: Long?,
)

/** BufferPoolMXBean covers these buffers only, not all native memory. */
@Serializable
public data class JvmBufferSnapshot(
    public val count: Long,
    @SerialName("used_bytes") public val usedBytes: Long?,
    @SerialName("capacity_bytes") public val capacityBytes: Long,
)
