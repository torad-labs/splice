// NEW: a GC-owned container keeps its charge while any retained reference still owns it.
package splice.core.memory

import java.lang.ref.Cleaner

/** For owners without an explicit disposal seam, refund only after the actual container becomes unreachable.
 *  The cleanup action retains the lease, never the owner. Explicit cleanup may close the same lease safely.
 */
public object HeapOwners {
    private val cleaner = Cleaner.create()

    public fun <O : Any> charge(owner: O, heap: HeapReservations, bytes: Long): HeapLease {
        val lease = heap.reserve(bytes) ?: throw HeapCapacityException()
        keep(owner, lease)
        return lease
    }

    /** Categorized acquisition preserves the existing GC-owned lifetime and capacity refusal. */
    public fun <O : Any> charge(owner: O, heap: HeapReservations, bytes: Long, category: HeapCategory): HeapLease {
        val lease = heap.reserve(bytes, category) ?: throw HeapCapacityException()
        keep(owner, lease)
        return lease
    }

    /** Bind an uncharged estimate to an escaped owner; the Cleaner retains only its ticket metadata. */
    public fun <O : Any> observe(
        owner: O,
        heap: HeapReservations,
        estimatedRetainedBytes: Long,
        category: HeapCategory,
        state: HeapOwnerState = HeapOwnerState.ACTIVE,
    ): HeapObservation {
        val observation = heap.observe(estimatedRetainedBytes, category, state)
        keep(owner, observation)
        return observation
    }

    /** Cleaner registration is not logical completion. Explicit replacement/eviction can close this same ticket. */
    public fun <O : Any> keep(owner: O, observation: HeapObservation) {
        val _ = cleaner.register(owner, Runnable { observation.close(HeapRelease.COLLECTED) })
    }

    /** Transfer a shared charge to an escaped container before releasing the previous owner.
     *  Registration does not end logical ownership. The terminal owner explicitly calls ownerFinished().
     */
    public fun <O : Any> keep(owner: O, lease: HeapLease) {
        val _ = cleaner.register(owner, Runnable { lease.close(HeapRelease.COLLECTED) })
    }
}
