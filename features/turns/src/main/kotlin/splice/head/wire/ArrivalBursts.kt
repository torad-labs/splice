// NEW: V4-456 — the shape of a turn's visible deltas as they ARRIVE at the client write path, before the pacer.
package splice.head.wire

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf

/** Two visible deltas closer than this are one burst: it is the pacer's own tick, the distance inside which
 *  [DeltaPacer] holds a delta rather than writing it, so a burst here is exactly what the pacer has to spread. */
internal const val BURST_GAP_MS = PACE_TICK_MS

/** Groups a turn's visible deltas into bursts by host-clock arrival gap and records, on the turn's perf row, how
 *  many bursts there were, the largest in deltas, and the longest silence a burst broke. The trace stores
 *  joined bytes under one duration and a perf row counts whole turns, so without this a provider that sends
 *  190 deltas in one read and one that sends them evenly leave the same row. Runs under the channel's
 *  writeMutex, like the pacer; it reads the clock the caller hands it and writes nothing to the client. */
internal class ArrivalBursts(private val gapMs: Long = BURST_GAP_MS) {
    private var lastMs: Long? = null
    private var size = 0L

    fun arrived(perf: TurnPerf, nowMs: Long) {
        val last = lastMs
        lastMs = nowMs
        if (last != null && nowMs - last < gapMs) {
            size += 1
        } else {
            size = 1
            perf.add(PerfKeys.ARRIVAL_BURSTS, 1)
            if (last != null) perf.maxCount(PerfKeys.ARRIVAL_SILENCE_MAX_MS, nowMs - last)
        }
        perf.maxCount(PerfKeys.ARRIVAL_BURST_MAX_DELTAS, size)
    }
}
