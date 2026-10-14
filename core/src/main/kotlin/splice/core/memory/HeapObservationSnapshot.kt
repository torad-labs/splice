package splice.core.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Separate, potentially overlapping owner estimates. Never add these to charges or JVM memory observations. */
@Serializable
public data class HeapObservationSnapshot(
    @SerialName("estimate_scope") public val estimateScope: String,
    @SerialName("estimated_retained_bytes") public val estimatedRetainedBytes: Long,
    @SerialName("peak_estimated_retained_bytes") public val peakEstimatedRetainedBytes: Long,
    @SerialName("shared_age_capacity") public val sharedAgeCapacity: Int,
    public val categories: List<HeapObservedCategorySnapshot>,
)

/** Observation count means live estimate tickets, not unique JVM objects or admission allocations. */
@Serializable
public data class HeapObservedCategorySnapshot(
    public val category: HeapCategory,
    public val observations: Long,
    public val owners: Long,
    @SerialName("estimated_retained_bytes") public val estimatedRetainedBytes: Long,
    @SerialName("peak_observations") public val peakObservations: Long,
    @SerialName("peak_owners") public val peakOwners: Long,
    @SerialName("peak_estimated_retained_bytes") public val peakEstimatedRetainedBytes: Long,
    public val releases: HeapObservedReleaseSnapshot,
    public val states: List<HeapObservedStateSnapshot>,
    public val ages: HeapObservedAgeSnapshot,
) {
    public val releasedOwnersTotal: Long get() = releases.releasedOwnersTotal
    public val retiredObservationsTotal: Long get() = releases.retiredObservationsTotal
    public val removedEstimatedBytesTotal: Long get() = releases.removedEstimatedBytesTotal
    public val evictedOwnersTotal: Long get() = releases.evictedOwnersTotal
    public val collectionObservedOwnersTotal: Long get() = releases.collectionObservedOwnersTotal
}

/** Cumulative estimate-owner releases, kept separate from charged refunds and process-memory measurements. */
@Serializable
public data class HeapObservedReleaseSnapshot(
    @SerialName("released_owners_total") public val releasedOwnersTotal: Long,
    @SerialName("retired_observations_total") public val retiredObservationsTotal: Long,
    @SerialName("removed_estimated_bytes_total") public val removedEstimatedBytesTotal: Long,
    @SerialName("evicted_owners_total") public val evictedOwnersTotal: Long,
    @SerialName("collection_observed_owners_total") public val collectionObservedOwnersTotal: Long,
)

/** Bounded age coverage counts observation tickets, never charged allocations. */
@Serializable
public data class HeapObservedAgeSnapshot(
    @SerialName("tracked_observations") public val trackedObservations: Long,
    @SerialName("untracked_observations") public val untrackedObservations: Long,
    @SerialName("overflow_total") public val overflowTotal: Long,
    @SerialName("oldest_tracked_age_ms") public val oldestTrackedAgeMs: Long?,
    @SerialName("tracked_under_1s") public val trackedUnder1s: Long,
    @SerialName("tracked_1s_to_1m") public val tracked1sTo1m: Long,
    @SerialName("tracked_1m_to_5m") public val tracked1mTo5m: Long,
    @SerialName("tracked_5m_to_1h") public val tracked5mTo1h: Long,
    @SerialName("tracked_over_1h") public val trackedOver1h: Long,
)

/** Shared estimate bytes use RUNNING, then QUEUED, then ACTIVE, then all owners awaiting collection. */
@Serializable
public data class HeapObservedStateSnapshot(
    public val state: HeapOwnerState,
    public val owners: Long,
    public val observations: Long,
    @SerialName("estimated_retained_bytes") public val estimatedRetainedBytes: Long,
)
