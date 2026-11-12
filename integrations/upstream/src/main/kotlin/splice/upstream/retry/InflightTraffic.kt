package splice.upstream.retry

import splice.core.head.GateCounts
import splice.core.head.GateSlot

/** Cumulative delivery accounting and live readings; every access holds the owning gate's lock. */
internal class InflightTraffic {
    // Insertion order is admission order, so the oldest held slot remains the first live reading.
    private val live = LinkedHashSet<InflightGate.Slot>()
    private var acquired = 0L
    private var released = 0L
    private var waited = 0L
    private var waitMsTotal = 0L
    var refused = 0L
        private set
    var queuedCancelled = 0L
        private set

    fun delivered(slot: InflightGate.Slot, waitedMs: Long?) {
        acquired += 1
        if (waitedMs != null) {
            waited += 1
            waitMsTotal += waitedMs
        }
        live.add(slot)
    }

    fun release(slot: InflightGate.Slot) {
        if (live.remove(slot)) released += 1
    }

    fun refuse() { refused += 1 }

    fun cancelQueued() { queuedCancelled += 1 }

    fun counts(): GateCounts = GateCounts(
        acquired = acquired,
        released = released,
        waited = waited,
        avgWaitMs = if (waited == 0L) 0L else (waitMsTotal + waited / 2) / waited,
        refused = refused,
    )

    fun readings(now: Long): List<GateSlot> = live.map { it.readingState.reading(now) }
}
