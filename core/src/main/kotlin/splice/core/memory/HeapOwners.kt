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

    /** Transfer a shared charge to an escaped container before releasing the previous owner. */
    public fun <O : Any> keep(owner: O, lease: HeapLease) {
        val _ = cleaner.register(owner, Runnable { lease.close() })
    }
}
