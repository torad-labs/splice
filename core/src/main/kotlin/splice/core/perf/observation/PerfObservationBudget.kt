package splice.core.perf.observation

/** Pure scalar inspection; does not collect released owners, generate a snapshot, or reserve memory. */
public object PerfObservationBudget {
    public fun status(): PerfMemoryStatus = PerfMemoryBudget.status()
}

public data class PerfMemoryStatus(
    val reservedBytes: Long,
    val allocatableLimitBytes: Long,
    val totalLimitBytes: Long,
    val snapshotActive: Boolean,
    val snapshotReserveBytes: Long,
    val headroomBytes: Long,
)
