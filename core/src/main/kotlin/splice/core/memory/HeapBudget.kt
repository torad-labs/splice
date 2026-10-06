// NEW: one byte ledger for every heap owner in the daemon.
package splice.core.memory

import kotlinx.coroutines.flow.StateFlow

/** Resident allowance kept outside the ledger for framework, runtime and small control objects. */
public const val HEAP_RESIDENT_BYTES: Long = 384 * 1024 * 1024L

// why: charges are estimates, not live bytes; G1 evacuation and uncharged copies need half the heap.
private const val HEAP_LEDGER_DIVISOR = 2L

/** Waitable process root. Only bounded read views can close admission; leases keep their actual owner. */
public class HeapBudget(
    heapLimitBytes: Long,
    budgetBytes: Long = 0L,
) : HeapReservations() {
    override val limitBytes: Long = minOf(
        (heapLimitBytes - minOf(HEAP_RESIDENT_BYTES, heapLimitBytes / 2)).coerceAtLeast(1L),
        (heapLimitBytes / HEAP_LEDGER_DIVISOR).coerceAtLeast(1L),
    ).let { if (budgetBytes > 0L) minOf(it, budgetBytes) else it }
    private val ledger = HeapLedger(limitBytes)

    /** Process availability is sufficient to retry a root reservation, never a saturated child reservation. */
    public val available: StateFlow<Long> = ledger.available

    override fun readShare(): HeapReadView = HeapReadView(ledger.readShare())

    override fun reserve(bytes: Long): HeapLease? = ledger.reserve(bytes)
}

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
