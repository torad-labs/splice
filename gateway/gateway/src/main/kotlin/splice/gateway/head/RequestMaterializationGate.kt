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

    public companion object {
        public const val DEFAULT_MAX_CONCURRENT: Int = 16
    }
}
