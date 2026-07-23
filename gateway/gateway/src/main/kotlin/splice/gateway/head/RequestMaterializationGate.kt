// NEW: process-shared memory admission for request decoding and translation.
package splice.gateway.head

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Process-shared bound on requests concurrently being decoded and translated.
 *
 * The turn gate bounds long-lived upstream work; this smaller gate bounds the short, allocation-
 * heavy phase that temporarily holds the raw UTF-8 body, Anthropic tree, and translated tree.
 */
public class RequestMaterializationGate(maxConcurrent: Int = DEFAULT_MAX_CONCURRENT) {
    private val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))

    public suspend fun <T> withLease(block: suspend () -> T): T = semaphore.withPermit { block() }

    /** Non-suspending lease attempt — null when every permit is busy. Cheap best-effort endpoints
     *  (count_tokens) fast-fail on contention instead of queueing unboundedly on the permits real
     *  turns materialize through (review 2026-07-22: a slow-body count_tokens flood could camp the
     *  process-shared semaphore and stall every head's turn materialization). Null is exclusively
     *  the contention signal; the `Any` bound makes a legitimately-null block result unrepresentable
     *  at compile time (review 2026-07-22 round 3). */
    public suspend fun <T : Any> tryWithLease(block: suspend () -> T): T? {
        if (!semaphore.tryAcquire()) return null
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_CONCURRENT: Int = 16
    }
}
