// NEW: allocation accounting metadata stays with its existing owners, never in a tracking reference set.
package splice.core.memory

import splice.core.util.MonoClock

/** Mutated only under the ancestry ledger's one lock. */
internal class HeapAllocation(
    var bytes: Long,
    var category: HeapCategory,
    state: HeapOwnerState,
    val startedAtMs: Long = MonoClock.nowMs(),
    val accounting: HeapAccounting = HeapAccounting.CHARGED,
) {
    var owners: Int = 1
        private set
    var ageSlot: Int = -1
    private var active = if (state == HeapOwnerState.ACTIVE) 1 else 0
    private var queued = if (state == HeapOwnerState.QUEUED) 1 else 0
    private var running = if (state == HeapOwnerState.RUNNING) 1 else 0
    private var awaiting = if (state == HeapOwnerState.AWAITING_COLLECTION) 1 else 0

    fun owners(state: HeapOwnerState): Int = when (state) {
        HeapOwnerState.ACTIVE -> active
        HeapOwnerState.QUEUED -> queued
        HeapOwnerState.RUNNING -> running
        HeapOwnerState.AWAITING_COLLECTION -> awaiting
    }

    fun adjust(state: HeapOwnerState, delta: Int) {
        when (state) {
            HeapOwnerState.ACTIVE -> active += delta
            HeapOwnerState.QUEUED -> queued += delta
            HeapOwnerState.RUNNING -> running += delta
            HeapOwnerState.AWAITING_COLLECTION -> awaiting += delta
        }
        owners += delta
    }

    /** Shared bytes stay live until every handle is explicitly logically done. */
    fun chargedState(): HeapOwnerState = when {
        owners(HeapOwnerState.RUNNING) > 0 -> HeapOwnerState.RUNNING
        owners(HeapOwnerState.QUEUED) > 0 -> HeapOwnerState.QUEUED
        owners(HeapOwnerState.ACTIVE) > 0 -> HeapOwnerState.ACTIVE
        else -> HeapOwnerState.AWAITING_COLLECTION
    }
}
