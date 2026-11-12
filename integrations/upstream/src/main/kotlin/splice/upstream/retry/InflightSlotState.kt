// NEW: retained readers and continuation handles share one permit's ownership and live reading. The gate's live
// limit sits beside it, moved out of InflightGate.kt unchanged, so the gate file declares the gate alone.
package splice.upstream.retry

import splice.core.head.GatePhase
import splice.core.head.GateSlot
import splice.core.util.ElapsedClock
import splice.upstream.TurnEnd
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class InflightSlotState(clock: ElapsedClock) {
    val ownership = Any()
    var owners = 1
    var readers = 0
    var continuation = false

    @Volatile var finalized = false

    val admittedAt = clock()
    val lastTouch = AtomicLong(admittedAt)
    val onRelease = ConcurrentLinkedQueue<TurnEnd>()

    @Volatile var upstreamBytes: InflightGate.Slot.UpstreamBytes? = null

    @Volatile var label: String = "req"

    @Volatile var compact: Boolean = false

    @Volatile var streaming: Boolean = false

    /** The permit was given back while the turn holds for its cause to lift, and not yet taken again (slice 5). */
    val yielded = AtomicBoolean(false)

    fun reading(now: Long): GateSlot = GateSlot(
        label = label,
        compact = compact,
        phase = if (streaming) GatePhase.STREAMING else GatePhase.CONNECT,
        ageMs = now - admittedAt,
        idleMs = now - lastTouch.get(),
        turnId = upstreamBytes?.turnId,
    )

    fun drainOnRelease() {
        generateSequence { onRelease.poll() }.forEach { it.ended() }
    }
}

/**
 * A concurrency limit READ FRESH at every admission decision, never captured at construction.
 *
 * That freshness is the port's entire reason to exist and the reason kotlinx `Semaphore` is banned
 * in [InflightGate]: a live `PATCH` of `maxInflight` must change the next admission without a head
 * restart, and a semaphore cannot hot-resize. `0` means unlimited, the same convention for both
 * limits.
 *
 * Distinct from a GAUGE (`ControlServer.failedHeads`), which is also `() -> Int` but reports what
 * IS rather than bounding what may happen next.
 */
public fun interface LiveLimit {
    public operator fun invoke(): Int
}
