// NEW: heap ownership can be handed to another lifetime without an uncharged interval.
package splice.core.memory

/** An idempotent owner of a shared reservation. The last owner returns its charged bytes. */
public class HeapLease internal constructor(
    private val budget: HeapBudget,
    private val allocation: HeapAllocation,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    public val bytes: Long get() = synchronized(lock) {
        check(!closed)
        budget.bytes(allocation)
    }

    /** Share before releasing the old owner. A detached turn therefore keeps the same charge. */
    public fun share(): HeapLease = synchronized(lock) {
        check(!closed)
        budget.share(allocation)
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
        budget.split(allocation, bytes)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            budget.release(allocation)
        }
    }
}
