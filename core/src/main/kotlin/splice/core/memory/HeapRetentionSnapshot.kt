// NEW: scalar diagnostic snapshots; charge refunds do not measure JVM object reclamation.
package splice.core.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Process-root charges only. Read-view ancestors and shared handles are never charged twice. */
@Serializable
public data class HeapRetentionSnapshot(
    @SerialName("limit_bytes") public val limitBytes: Long,
    @SerialName("estimated_charged_bytes") public val estimatedChargedBytes: Long,
    @SerialName("peak_estimated_charged_bytes") public val peakEstimatedChargedBytes: Long,
    @SerialName("age_capacity") public val ageCapacity: Int,
    public val categories: List<HeapCategorySnapshot>,
)

/** Allocation state is RUNNING, then QUEUED, then ACTIVE, then all owners awaiting collection. */
@Serializable
public data class HeapCategorySnapshot(
    public val category: HeapCategory,
    public val allocations: Long,
    public val owners: Long,
    @SerialName("estimated_charged_bytes") public val estimatedChargedBytes: Long,
    @SerialName("peak_allocations") public val peakAllocations: Long,
    @SerialName("peak_owners") public val peakOwners: Long,
    @SerialName("peak_estimated_charged_bytes") public val peakEstimatedChargedBytes: Long,
    @SerialName("released_owners_total") public val releasedOwnersTotal: Long,
    @SerialName("refunded_allocations_total") public val refundedAllocationsTotal: Long,
    @SerialName("refunded_estimated_bytes_total") public val refundedEstimatedBytesTotal: Long,
    @SerialName("evicted_owners_total") public val evictedOwnersTotal: Long,
    @SerialName("collection_observed_owners_total") public val collectionObservedOwnersTotal: Long,
    public val states: List<HeapStateSnapshot>,
    public val ages: HeapAgeSnapshot,
)

/** Handle counts may exceed allocation counts; estimated bytes belong to allocations, not handles. */
@Serializable
public data class HeapStateSnapshot(
    public val state: HeapOwnerState,
    public val owners: Long,
    public val allocations: Long,
    @SerialName("estimated_charged_bytes") public val estimatedChargedBytes: Long,
)

/** Ages refer to first reservation, inherited by splits. Incomplete coverage is explicitly visible. */
@Serializable
public data class HeapAgeSnapshot(
    @SerialName("tracked_allocations") public val trackedAllocations: Long,
    @SerialName("untracked_allocations") public val untrackedAllocations: Long,
    @SerialName("overflow_total") public val overflowTotal: Long,
    @SerialName("oldest_tracked_age_ms") public val oldestTrackedAgeMs: Long?,
    @SerialName("tracked_under_1s") public val trackedUnder1s: Long,
    @SerialName("tracked_1s_to_1m") public val tracked1sTo1m: Long,
    @SerialName("tracked_1m_to_5m") public val tracked1mTo5m: Long,
    @SerialName("tracked_5m_to_1h") public val tracked5mTo1h: Long,
    @SerialName("tracked_over_1h") public val trackedOver1h: Long,
)
