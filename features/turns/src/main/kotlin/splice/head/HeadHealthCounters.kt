// NEW: G20 cheap in-memory per-head passive health counters, split local-origin vs
// provider-error (Envoy split_external_local_origin_errors shape). Diagnosis instrument only —
// reset on head restart, no persistence (PerfStats/JSONL already owns durable per-turn rows).
package splice.head

import java.util.concurrent.atomic.AtomicLong

internal class HeadHealthCounters {
    private val localOrigin = AtomicLong(0)
    private val providerError = AtomicLong(0)
    private val rateLimitTurns = AtomicLong(0)
    private val cooldownHeldTurns = AtomicLong(0)

    fun local() {
        localOrigin.incrementAndGet()
    }

    fun provider() {
        providerError.incrementAndGet()
    }

    fun failure(outcome: splice.core.turn.TurnOutcome.Failure) {
        if (outcome.traits.providerReported) provider() else local()
        if (outcome.traits.providerReported && outcome.cause == splice.core.turn.FailureCause.VENDOR_RATE_LIMITED) {
            rateLimited()
        }
    }

    /** Called only at final-turn classification, never from absorbed-round signals. */
    fun rateLimited() {
        rateLimitTurns.incrementAndGet()
    }

    fun cooldownHeld() {
        cooldownHeldTurns.incrementAndGet()
    }

    fun rateLimitSnapshot(): splice.core.head.RateLimitHealth =
        splice.core.head.RateLimitHealth(rateLimitTurns.get(), cooldownHeldTurns.get())

    fun snapshot(): HeadHealthCounts = HeadHealthCounts(localOrigin.get(), providerError.get())

    /** Fresh diagnostic baseline on head restart — the documented contract (review 2026-07-19). */
    fun reset() {
        localOrigin.set(0)
        providerError.set(0)
        rateLimitTurns.set(0)
        cooldownHeldTurns.set(0)
    }
}

internal data class HeadHealthCounts(val localOrigin: Long, val providerError: Long)
