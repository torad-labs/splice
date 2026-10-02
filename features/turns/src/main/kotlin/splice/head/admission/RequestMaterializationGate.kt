// NEW: process-shared memory admission for request decoding and translation.
package splice.head.admission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import splice.core.config.Knob
import splice.head.turn.MaterializedRequest
import splice.upstream.TurnEnd
import java.util.concurrent.atomic.AtomicBoolean

// Measured everyday daemon footprint, reserved before request admission (V4-374).
internal const val MATERIALIZATION_RESIDENT_BYTES: Long = 384 * 1024 * 1024L

// why: the measured 208 MiB for a 32 MiB body is a 13/2 heap expansion, rounded upward.
private const val HEAP_EXPANSION_NUMERATOR = 13L

// why: retain the measured half-byte expansion without floating-point admission arithmetic.
private const val HEAP_EXPANSION_DENOMINATOR = 2L

/**
 * Process-shared heap budget for decoding, translation and the request trees retained by a turn.
 *
 * A body reserves 6.5 times its bytes: the measured 208 MiB heap for a 32 MiB body. Zero selects
 * the JVM maximum heap minus the lesser of the resident allowance and half the heap. An override
 * can only lower that budget. Bodies heavier than the entire budget reserve it exclusively.
 */
public class RequestMaterializationGate(
    heapBudgetBytes: Long = Knob.MATERIALIZATION_HEAP_BYTES.default as Long,
    heapLimitBytes: Long = Runtime.getRuntime().maxMemory(),
) {
    private val spare = (heapLimitBytes - minOf(MATERIALIZATION_RESIDENT_BYTES, heapLimitBytes / 2)).coerceAtLeast(1L)
    private val budget = if (heapBudgetBytes <= 0L) spare else minOf(heapBudgetBytes, spare)
    private val available = MutableStateFlow(budget)
    private val lock = Any()

    /** Wait for enough bytes, not a request-count slot. Bodies larger than the budget run alone. */
    internal suspend fun <T : Any> withLease(
        bodyBytes: Long,
        owner: MaterializationOwner? = null,
        block: MaterializedRequest<T>,
    ): T? {
        val weight = weight(bodyBytes)
        while (!acquire(weight)) available.first { it >= weight }
        return leased(weight, owner, block)
    }

    /** count_tokens never queues. Null exclusively means insufficient heap admission capacity. */
    internal suspend fun <T : Any> tryWithLease(bodyBytes: Long, block: MaterializedRequest<T>): T? {
        val weight = weight(bodyBytes)
        if (!acquire(weight)) return null
        return leased(weight, block = block)
    }

    private fun weight(bodyBytes: Long): Long {
        val bytes = bodyBytes.coerceAtLeast(1L)
        if (bytes > (Long.MAX_VALUE - 1L) / HEAP_EXPANSION_NUMERATOR) return budget
        val weight = (bytes * HEAP_EXPANSION_NUMERATOR + 1L) / HEAP_EXPANSION_DENOMINATOR
        return minOf(weight, budget)
    }

    private fun acquire(weight: Long): Boolean = synchronized(lock) {
        if (available.value < weight) {
            false
        } else {
            available.value -= weight
            true
        }
    }

    private suspend fun <T : Any> leased(
        weight: Long,
        owner: MaterializationOwner? = null,
        block: MaterializedRequest<T>,
    ): T {
        val released = AtomicBoolean()
        val release = TurnEnd {
            if (released.compareAndSet(false, true)) synchronized(lock) { available.value += weight }
        }
        var retained = false
        return try {
            if (owner != null) {
                owner.onRelease(release)
            }
            val materialized = block()
            retained = owner != null
            materialized
        } finally {
            if (!retained) release.ended()
        }
    }
}

/** A retained request's owner releases its heap lease only when its request trees are no longer live. */
internal fun interface MaterializationOwner {
    fun onRelease(release: TurnEnd)
}
