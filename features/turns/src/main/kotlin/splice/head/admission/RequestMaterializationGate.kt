// NEW: decoding, ingress and retained owners spend the same process heap ledger.
package splice.head.admission

import kotlinx.coroutines.flow.first
import splice.core.config.Knob
import splice.core.memory.HEAP_RESIDENT_BYTES
import splice.core.memory.HeapBudget
import splice.core.memory.HeapLease
import splice.core.memory.HeapWeights
import splice.head.turn.MaterializedRequest
import splice.upstream.TurnEnd
import splice.upstream.memory.JvmHeap

internal const val MATERIALIZATION_RESIDENT_BYTES: Long = HEAP_RESIDENT_BYTES

/** Decoding and retained request trees use the measured 13/2 expansion, without oversized clamping. */
public class RequestMaterializationGate(
    heapBudgetBytes: Long = Knob.MATERIALIZATION_HEAP_BYTES.default as Long,
    heapLimitBytes: Long = JvmHeap.limitBytes,
    /** Production injects the daemon ledger, shared with every retained owner and both listeners. */
    public val heap: HeapBudget = HeapBudget(heapLimitBytes, heapBudgetBytes),
) {
    public val limitBytes: Long =
        if (heapBudgetBytes > 0L) minOf(heapBudgetBytes, heap.limitBytes) else heap.limitBytes

    public fun requestBytes(bodyBytes: Long): Long = HeapWeights.request(bodyBytes)

    internal suspend fun <T : Any> withLease(
        bodyBytes: Long,
        owner: MaterializationOwner? = null,
        reservation: HeapLease? = null,
        block: MaterializedRequest<T>,
    ): T? {
        if (reservation != null) return leased(reservation, owner, block)
        val weight = requestBytes(bodyBytes)
        if (weight > limitBytes) return null
        var lease = heap.reserve(weight)
        while (lease == null) {
            heap.available.first { it >= weight }
            lease = heap.reserve(weight)
        }
        return leased(lease, owner, block)
    }

    /** count_tokens does not queue. Null only means insufficient heap admission capacity. */
    internal suspend fun <T : Any> tryWithLease(
        bodyBytes: Long,
        reservation: HeapLease? = null,
        block: MaterializedRequest<T>,
    ): T? {
        val weight = requestBytes(bodyBytes)
        if (reservation == null && weight > limitBytes) return null
        val lease = reservation ?: heap.reserve(weight) ?: return null
        return leased(lease, block = block)
    }

    private suspend fun <T : Any> leased(
        lease: HeapLease,
        owner: MaterializationOwner? = null,
        block: MaterializedRequest<T>,
    ): T {
        val release = TurnEnd { lease.close() }
        var retained = false
        return try {
            owner?.onRelease(release)
            val materialized = block()
            retained = owner != null
            materialized
        } finally {
            if (!retained) release.ended()
        }
    }
}

/** A retained request's owner releases its charge only when its request trees are no longer live. */
internal fun interface MaterializationOwner {
    fun onRelease(release: TurnEnd)
}
