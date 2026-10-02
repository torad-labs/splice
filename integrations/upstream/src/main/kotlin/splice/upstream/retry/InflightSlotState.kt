// NEW: retained readers and continuation handles share one permit's ownership and live reading.
package splice.upstream.retry

import splice.core.util.ElapsedClock
import splice.upstream.TurnEnd
import java.util.concurrent.ConcurrentLinkedQueue
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
}
