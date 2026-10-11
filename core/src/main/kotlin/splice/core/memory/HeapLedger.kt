// NEW: root and non-waitable views share one atomic ancestry ledger and the actual allocation owners.
package splice.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// why: at 8 GiB, reads leave 1 GiB for admission versus the measured ~40 MiB normal in-flight charge.
private const val HEAP_READ_HEADROOM_DIVISOR = 4L

internal class HeapLedger(val limitBytes: Long, private val parent: HeapLedger? = null) {
    private val lock: Any = parent?.lock ?: Any()
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

    fun reserve(bytes: Long): HeapLease? = synchronized(lock) {
        require(bytes >= 0L)
        if (closed || !charge(bytes)) return@synchronized null
        HeapLease(this, HeapAllocation(bytes))
    }

    fun share(allocation: HeapAllocation): HeapLease = synchronized(lock) {
        check(allocation.owners > 0)
        allocation.owners++
        HeapLease(this, allocation)
    }

    fun resize(allocation: HeapAllocation, bytes: Long): Boolean = synchronized(lock) {
        require(bytes >= 0L)
        check(allocation.owners > 0)
        val growth = bytes - allocation.bytes
        if (!charge(growth)) return@synchronized false
        allocation.bytes = bytes
        true
    }

    fun split(allocation: HeapAllocation, bytes: Long): HeapLease = synchronized(lock) {
        check(allocation.owners == 1) { "a shared allocation cannot be partitioned" }
        require(bytes in 0..allocation.bytes)
        allocation.bytes -= bytes
        HeapLease(this, HeapAllocation(bytes))
    }

    fun bytes(allocation: HeapAllocation): Long = synchronized(lock) { allocation.bytes }

    fun release(allocation: HeapAllocation) {
        synchronized(lock) {
            check(allocation.owners > 0)
            allocation.owners--
            if (allocation.owners == 0) refund(allocation.bytes)
        }
    }
}

internal data class HeapAllocation(var bytes: Long, var owners: Int = 1)
