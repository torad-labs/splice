// The last wait of a head's drain: until no turn holds a gate slot and no call is still inside a route handler.
package splice.head

import splice.upstream.Waiter
import splice.upstream.retry.InflightGate

/** How often the wait looks again. */
private const val SEAL_POLL_MS = 50L

/** A turn's gate slot is released before its response's last chunk is written, so the gate reaching zero is not the
 *  end of a turn. The wait ends only when the gate is empty AND every call has left its handler, or at the deadline;
 *  stopping the engine sooner cuts the tail of a finished turn. */
internal class StopSeal(
    private val gate: InflightGate,
    private val calls: ActiveCalls,
    private val waiter: Waiter,
) {
    /** Waits, polling through the injected waiter, until the head is idle or [deadlineNs] (a nanoTime reading) passes. */
    suspend fun settle(deadlineNs: Long) {
        while (gate.snapshot().inflight + calls() > 0 && System.nanoTime() < deadlineNs) {
            waiter.wait(SEAL_POLL_MS)
        }
    }
}

/** How many calls are still inside a route handler: a head's engine counts its own ([HeadEngine]), and a gateway counts
 *  the calls it hands a provider runtime ([ProviderRuntime]). */
internal fun interface ActiveCalls {
    operator fun invoke(): Int
}
