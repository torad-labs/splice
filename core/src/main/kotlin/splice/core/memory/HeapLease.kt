// NEW: heap ownership can be handed to another lifetime without an uncharged interval.
package splice.core.memory

/** An idempotent owner of a shared reservation. The last owner returns its charged bytes. */
public class HeapLease internal constructor(
    private val budget: HeapLedger,
    private val allocation: HeapAllocation,
    private var ownerState: HeapOwnerState,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    public val bytes: Long get() = synchronized(lock) {
        check(!closed)
        budget.bytes(allocation)
    }

    /** Share before releasing the old owner. A new handle is live, even if the old owner is logically done. */
    public fun share(): HeapLease = share(HeapOwnerState.ACTIVE)

    /** Describe the new handle's work state without adding another byte charge. */
    public fun share(state: HeapOwnerState): HeapLease = synchronized(lock) {
        check(!closed)
        budget.share(allocation, state)
    }

    /** Reserve growth before allocating it. A failed growth leaves the existing charge unchanged. */
    public fun resize(bytes: Long): Boolean = synchronized(lock) {
        check(!closed)
        budget.resize(allocation, bytes)
    }

    /** Partition a sole-owned peak without refunding any bytes during the transfer.
     *  Shared peaks cannot be split because another owner still retains their whole allocation.
     */
    public fun split(bytes: Long): HeapLease = synchronized(lock) {
        check(!closed)
        budget.split(allocation, bytes, ownerState)
    }

    /** Transfer the allocation's category, including all its shared handles; admission is unchanged. */
    public fun transfer(category: HeapCategory) {
        synchronized(lock) {
            if (closed) return
            budget.transition(allocation, ownerState, ownerState, category)
        }
    }

    /** Only this handle changes work state. Closing/terminal races make diagnostic transitions a no-op. */
    public fun state(state: HeapOwnerState) {
        synchronized(lock) {
            if (closed || ownerState == state) return
            budget.transition(allocation, ownerState, state)
            ownerState = state
        }
    }

    /** An explicit terminal handoff, not Cleaner registration. The charge remains until the final close.
     *  With GC-owned containers this labels release-awaiting-collection candidates, not proven leaks.
     */
    public fun ownerFinished() {
        state(HeapOwnerState.AWAITING_COLLECTION)
    }

    override fun close() {
        close(HeapRelease.CLOSED)
    }

    /** Only an actual owner eviction uses EVICTED; the existing Cleaner cleanup uses COLLECTED. */
    public fun close(reason: HeapRelease) {
        synchronized(lock) {
            if (closed) return
            closed = true
            budget.release(allocation, ownerState, reason)
        }
    }
}
