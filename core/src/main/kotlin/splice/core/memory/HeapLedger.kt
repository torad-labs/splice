// NEW: root and non-waitable views share one atomic ancestry ledger and the actual allocation owners.
package splice.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// why: at 8 GiB, reads leave 1 GiB for admission versus the measured ~40 MiB normal in-flight charge.
private const val HEAP_READ_HEADROOM_DIVISOR = 4L

internal class HeapLedger(val limitBytes: Long, private val parent: HeapLedger? = null) {
    private val lock: Any = parent?.lock ?: Any()
    private val retention = parent?.retention ?: HeapRetention()
    private var free = limitBytes
    private val changes = if (parent == null) MutableStateFlow(limitBytes) else null
    private var closed = false
    private val readLimit: Long
        get() = parent?.readLimit ?: (limitBytes - limitBytes / HEAP_READ_HEADROOM_DIVISOR)

    /** Only the process root exposes this notification. Read views have no waiter contract. */
    val available: StateFlow<Long> get() = checkNotNull(changes).asStateFlow()

    fun readShare(): HeapLedger = synchronized(lock) {
        check(!closed)
        HeapLedger(minOf(limitBytes, readLimit), this)
    }

    fun close(): Unit = synchronized(lock) { closed = true }

    private fun charge(bytes: Long): Boolean {
        if (bytes > 0L && closed) return false
        if (bytes > free || parent?.charge(bytes) == false) return false
        free -= bytes
        changes?.value = free
        return true
    }

    private fun refund(bytes: Long) {
        free += bytes
        changes?.value = free
        parent?.refund(bytes)
    }

    fun reserve(
        bytes: Long,
        category: HeapCategory = HeapCategory.UNCLASSIFIED,
        state: HeapOwnerState = HeapOwnerState.ACTIVE,
    ): HeapLease? = synchronized(lock) {
        require(bytes >= 0L)
        if (closed || !charge(bytes)) return@synchronized null
        val allocation = HeapAllocation(bytes, category, state)
        retention.acquire(allocation)
        HeapLease(this, allocation, state)
    }

    fun share(allocation: HeapAllocation, state: HeapOwnerState): HeapLease = synchronized(lock) {
        check(allocation.owners > 0)
        retention.remove(allocation)
        allocation.adjust(state, 1)
        retention.record(allocation)
        HeapLease(this, allocation, state)
    }

    fun resize(allocation: HeapAllocation, bytes: Long): Boolean = synchronized(lock) {
        require(bytes >= 0L)
        check(allocation.owners > 0)
        val growth = bytes - allocation.bytes
        if (!charge(growth)) return@synchronized false
        retention.remove(allocation)
        if (growth < 0L) retention.shrunk(allocation, -growth)
        allocation.bytes = bytes
        retention.record(allocation)
        true
    }

    fun split(allocation: HeapAllocation, bytes: Long, state: HeapOwnerState): HeapLease = synchronized(lock) {
        check(allocation.owners == 1) { "a shared allocation cannot be partitioned" }
        require(bytes in 0..allocation.bytes)
        retention.remove(allocation)
        allocation.bytes -= bytes
        retention.record(allocation)
        val partition = HeapAllocation(bytes, allocation.category, state, allocation.startedAtMs)
        retention.acquire(partition)
        HeapLease(this, partition, state)
    }

    fun bytes(allocation: HeapAllocation): Long = synchronized(lock) { allocation.bytes }

    fun transition(
        allocation: HeapAllocation,
        from: HeapOwnerState,
        to: HeapOwnerState,
        category: HeapCategory? = null,
    ): Unit = synchronized(lock) {
        retention.remove(allocation)
        allocation.adjust(from, -1)
        allocation.adjust(to, 1)
        if (category != null) retention.transfer(allocation, category)
        retention.record(allocation)
    }

    fun release(allocation: HeapAllocation, state: HeapOwnerState, reason: HeapRelease) {
        synchronized(lock) {
            check(allocation.owners > 0)
            retention.remove(allocation)
            allocation.adjust(state, -1)
            if (allocation.owners == 0) refund(allocation.bytes)
            retention.released(allocation, reason)
        }
    }

    /** Called on the root so every read-view charge appears exactly once in the process summary. */
    fun retentionSnapshot(): HeapRetentionSnapshot = synchronized(lock) { retention.snapshot(limitBytes) }
}
