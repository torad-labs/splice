// NEW: one byte ledger for every heap owner in the daemon.
package splice.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Resident allowance kept outside the ledger for framework, runtime and small control objects. */
public const val HEAP_RESIDENT_BYTES: Long = 384 * 1024 * 1024L

// why: charges are estimates, not live bytes; G1 evacuation and uncharged copies need half the heap.
private const val HEAP_LEDGER_DIVISOR = 2L

// why: at 8 GiB, reads leave 1 GiB for admission versus the measured ~40 MiB normal in-flight charge.
private const val HEAP_READ_HEADROOM_DIVISOR = 4L

/** One process ledger with bounded child views. Closing a view never refunds retained lease owners. */
public class HeapBudget(
    heapLimitBytes: Long,
    budgetBytes: Long = 0L,
) : AutoCloseable {
    private val monitor = Any()

    // Assigned only by readShare, before the child escapes its factory.
    private var parent: HeapBudget? = null
    private val lock: Any get() = parent?.lock ?: monitor
    public val limitBytes: Long = minOf(
        (heapLimitBytes - minOf(HEAP_RESIDENT_BYTES, heapLimitBytes / 2)).coerceAtLeast(1L),
        (heapLimitBytes / HEAP_LEDGER_DIVISOR).coerceAtLeast(1L),
    ).let { if (budgetBytes > 0L) minOf(it, budgetBytes) else it }
    private val readLimit: Long
        get() = parent?.readLimit ?: (limitBytes - limitBytes / HEAP_READ_HEADROOM_DIVISOR)
    private val free = MutableStateFlow(limitBytes)
    private val observed = free.asStateFlow()
    private var closed = false

    /** Process availability wakes all views; a child also enforces its own cumulative quota on admission. */
    public val available: StateFlow<Long> get() = parent?.available ?: observed

    /** Open one read view. Nested views inherit the same ceiling and debit every ancestor atomically. */
    public fun readShare(): HeapBudget = synchronized(lock) {
        check(!closed)
        val bytes = minOf(limitBytes, readLimit)
        HeapBudget(bytes * HEAP_LEDGER_DIVISOR, bytes).also { it.parent = this }
    }

    /** End admission on every read exit. Escaped strings keep their root charge until their leases end. */
    override fun close(): Unit = synchronized(lock) { closed = true }

    private fun charge(bytes: Long): Boolean {
        if (bytes > 0L && closed) return false
        if (bytes > free.value || parent?.charge(bytes) == false) return false
        free.value -= bytes
        return true
    }

    private fun refund(bytes: Long) {
        free.value += bytes
        parent?.refund(bytes)
    }

    /** Null means capacity, including a reservation heavier than the whole budget. No weight is clamped. */
    public fun reserve(bytes: Long): HeapLease? = synchronized(lock) {
        require(bytes >= 0L)
        if (closed || !charge(bytes)) return@synchronized null
        HeapLease(this, HeapAllocation(bytes))
    }

    internal fun share(allocation: HeapAllocation): HeapLease = synchronized(lock) {
        check(allocation.owners > 0)
        allocation.owners++
        HeapLease(this, allocation)
    }

    internal fun resize(allocation: HeapAllocation, bytes: Long): Boolean = synchronized(lock) {
        require(bytes >= 0L)
        check(allocation.owners > 0)
        val growth = bytes - allocation.bytes
        if (!charge(growth)) return@synchronized false
        allocation.bytes = bytes
        true
    }

    internal fun split(allocation: HeapAllocation, bytes: Long): HeapLease = synchronized(lock) {
        check(allocation.owners == 1) { "a shared allocation cannot be partitioned" }
        require(bytes in 0..allocation.bytes)
        allocation.bytes -= bytes
        HeapLease(this, HeapAllocation(bytes))
    }

    internal fun bytes(allocation: HeapAllocation): Long = synchronized(lock) { allocation.bytes }

    internal fun release(allocation: HeapAllocation) {
        synchronized(lock) {
            check(allocation.owners > 0)
            allocation.owners--
            if (allocation.owners == 0) refund(allocation.bytes)
        }
    }
}

internal data class HeapAllocation(var bytes: Long, var owners: Int = 1)

/** Saturating byte arithmetic prevents malformed sizes from wrapping into a cheap reservation. */
public object HeapWeights {
    // why: V4-374 measured 208 MiB of request heap for a 32 MiB wire body.
    private const val REQUEST_NUMERATOR = 13L

    /** One charged socket decoder and its bounded raw read buffer. */
    public const val CONNECTION_BYTES: Long = 64 * 1024L

    // why: headers, per-call channels and ordering metadata exist before any decoded body.
    private const val REQUEST_METADATA_BYTES = 32 * 1024L

    /** Decoding and translation use the measured 13/2 request expansion, rounded upward. */
    public fun request(bytes: Long): Long =
        if (bytes > (Long.MAX_VALUE - 1L) / REQUEST_NUMERATOR) {
            Long.MAX_VALUE
        } else {
            (bytes.coerceAtLeast(1L) * REQUEST_NUMERATOR + 1L) / 2L
        }

    /** Full pre-decoding request ownership, including empty-body metadata, with saturating arithmetic. */
    public fun ingress(bytes: Long): Long {
        val body = request(bytes)
        return if (body > Long.MAX_VALUE - REQUEST_METADATA_BYTES) Long.MAX_VALUE else body + REQUEST_METADATA_BYTES
    }

    public fun multiply(bytes: Long, factor: Long): Long {
        require(bytes >= 0L && factor > 0L)
        return if (bytes > Long.MAX_VALUE / factor) Long.MAX_VALUE else bytes * factor
    }
}
