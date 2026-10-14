// Fixed scalar gauges, instantiated separately for charged allocations and uncharged observation tickets.
package splice.core.memory

internal class HeapCategoryCounts {
    var allocations: Long = 0L
        private set
    private var owners = 0L
    private val bytes = HeapByteCount()
    private var peakAllocations = 0L
    private var peakOwners = 0L
    private var peakBytes = 0L
    private var releasedOwners = 0L
    private var refundedAllocations = 0L
    private var refundedBytes = 0L
    private var evictedOwners = 0L
    private var collectedOwners = 0L
    private val stateOwners = LongArray(HeapOwnerState.entries.size)
    private val stateAllocations = LongArray(HeapOwnerState.entries.size)
    private val stateBytes = Array(HeapOwnerState.entries.size) { HeapByteCount() }

    fun adjust(allocation: HeapAllocation, direction: Long) {
        allocations += direction
        owners += allocation.owners * direction
        bytes.adjust(allocation.bytes, direction)
        HeapOwnerState.entries.forEach { state ->
            stateOwners[state.ordinal] += allocation.owners(state) * direction
        }
        val state = allocation.chargedState().ordinal
        stateAllocations[state] += direction
        stateBytes[state].adjust(allocation.bytes, direction)
        peakAllocations = maxOf(peakAllocations, allocations)
        peakOwners = maxOf(peakOwners, owners)
        peakBytes = maxOf(peakBytes, bytes.value)
    }

    fun released(allocation: HeapAllocation, reason: HeapRelease) {
        releasedOwners = total(releasedOwners, 1L)
        if (reason == HeapRelease.EVICTED) evictedOwners = total(evictedOwners, 1L)
        if (reason == HeapRelease.COLLECTED) collectedOwners = total(collectedOwners, 1L)
        if (allocation.owners == 0) {
            refundedAllocations = total(refundedAllocations, 1L)
            refundedBytes = total(refundedBytes, allocation.bytes)
        }
    }

    fun shrunk(bytes: Long) {
        refundedBytes = total(refundedBytes, bytes)
    }

    fun snapshot(category: HeapCategory, ages: HeapAgeSnapshot): HeapCategorySnapshot = HeapCategorySnapshot(
        category, allocations, owners, bytes.value, peakAllocations, peakOwners, peakBytes,
        HeapReleaseSnapshot(
            releasedOwners,
            refundedAllocations,
            refundedBytes,
            evictedOwners,
            collectedOwners,
        ),
        HeapOwnerState.entries.map { state ->
            val index = state.ordinal
            HeapStateSnapshot(state, stateOwners[index], stateAllocations[index], stateBytes[index].value)
        },
        ages,
    )

    fun observationSnapshot(category: HeapCategory, ages: HeapAgeSnapshot): HeapObservedCategorySnapshot =
        HeapObservedCategorySnapshot(
            category, allocations, owners, bytes.value, peakAllocations, peakOwners, peakBytes,
            HeapObservedReleaseSnapshot(
                releasedOwners,
                refundedAllocations,
                refundedBytes,
                evictedOwners,
                collectedOwners,
            ),
            HeapOwnerState.entries.map { state ->
                val index = state.ordinal
                HeapObservedStateSnapshot(state, stateOwners[index], stateAllocations[index], stateBytes[index].value)
            },
            observationAges(ages),
        )

    private fun observationAges(ages: HeapAgeSnapshot): HeapObservedAgeSnapshot = HeapObservedAgeSnapshot(
        ages.trackedAllocations,
        ages.untrackedAllocations,
        ages.overflowTotal,
        ages.oldestTrackedAgeMs,
        ages.trackedUnder1s,
        ages.tracked1sTo1m,
        ages.tracked1mTo5m,
        ages.tracked5mTo1h,
        ages.trackedOver1h,
    )

    private fun total(value: Long, delta: Long): Long =
        if (delta > Long.MAX_VALUE - value) Long.MAX_VALUE else value + delta
}
