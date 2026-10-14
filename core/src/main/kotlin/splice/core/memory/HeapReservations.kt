// NEW: read and retained owners reserve capacity without claiming the root's waiter notification contract.
package splice.core.memory

/** Reservation capability only. Admission waiters require [HeapBudget], never a bounded read view. */
public sealed class HeapReservations {
    public abstract val limitBytes: Long

    /** Null refuses capacity without waiting. The weight is never clamped. */
    public abstract fun reserve(bytes: Long): HeapLease?

    /** The same admission, annotated with closed category/state values for aggregate diagnostics. */
    public abstract fun reserve(
        bytes: Long,
        category: HeapCategory,
        state: HeapOwnerState = HeapOwnerState.ACTIVE,
    ): HeapLease?

    /** A nested read inherits the same ceiling and atomically debits every ancestor. */
    public abstract fun readShare(): HeapReadView
}
