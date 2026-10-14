package splice.core.memory

/**
 * An explicitly owned retained-size estimate with no admission charge or capacity refusal.
 * Independent tickets can overlap each other and charged leases. They do not measure process memory.
 * Sharing a ticket keeps one estimate until its final handle closes, just like a charged lease.
 */
public class HeapObservation internal constructor(private val lease: HeapLease) : AutoCloseable {
    public val estimatedRetainedBytes: Long get() = lease.bytes

    /** Update only this ticket's estimate, even when admission is saturated or its read view has closed. */
    public fun resize(estimatedRetainedBytes: Long) {
        check(lease.resize(estimatedRetainedBytes))
    }

    /** An escaped live owner shares the estimate without counting its bytes again. */
    public fun share(): HeapObservation = share(HeapOwnerState.ACTIVE)

    /** The new handle starts in the supplied logical work state. */
    public fun share(state: HeapOwnerState): HeapObservation = HeapObservation(lease.share(state))

    /** Reclassify this estimate and all its shared handles without charging admission. */
    public fun transfer(category: HeapCategory) {
        lease.transfer(category)
    }

    /** Change only this handle's logical state. Terminal/close races use the lease's idempotent transitions. */
    public fun state(state: HeapOwnerState) {
        lease.state(state)
    }

    /** A terminal handoff keeps the estimate visible until explicit cleanup or Cleaner observation. */
    public fun ownerFinished() {
        lease.ownerFinished()
    }

    override fun close() {
        lease.close()
    }

    /** Replacement uses CLOSED, actual eviction uses EVICTED, and Cleaner observation uses COLLECTED. */
    public fun close(reason: HeapRelease) {
        lease.close(reason)
    }
}
