// NEW: one byte ledger for every heap owner in the daemon.
package splice.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Resident allowance kept outside the ledger for framework, runtime and small control objects. */
public const val HEAP_RESIDENT_BYTES: Long = 384 * 1024 * 1024L

/** One process-wide ledger. Reservations are estimates, never a measurement of JVM live bytes. */
public class HeapBudget(
    heapLimitBytes: Long,
    budgetBytes: Long = 0L,
) {
    private val lock = Any()
    public val limitBytes: Long =
        (heapLimitBytes - minOf(HEAP_RESIDENT_BYTES, heapLimitBytes / 2)).coerceAtLeast(1L)
            .let { if (budgetBytes > 0L) minOf(it, budgetBytes) else it }
    private val free = MutableStateFlow(limitBytes)

    /** Availability changes wake callers without polling or holding an owner lock. */
    public val available: StateFlow<Long> = free.asStateFlow()

    /** Null means capacity, including a reservation heavier than the whole budget. No weight is clamped. */
    public fun reserve(bytes: Long): HeapLease? = synchronized(lock) {
        require(bytes >= 0L)
        if (bytes > free.value) return@synchronized null
        free.value -= bytes
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
        if (growth > free.value) return@synchronized false
        free.value -= growth
        allocation.bytes = bytes
        true
    }

    internal fun bytes(allocation: HeapAllocation): Long = synchronized(lock) { allocation.bytes }

    internal fun release(allocation: HeapAllocation) {
        synchronized(lock) {
            check(allocation.owners > 0)
            allocation.owners--
            if (allocation.owners == 0) free.value += allocation.bytes
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
