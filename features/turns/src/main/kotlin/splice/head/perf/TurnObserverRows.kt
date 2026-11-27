package splice.head.perf

import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.turn.ObservedUsage

/** Publish only local rows, not the console replay of rows imported from a predecessor. */
internal object TurnObserverRows {
    fun complete(perf: TurnPerf, outcome: String, snapshot: PerfSnapshot, stopReason: String? = null) {
        val observed = perf.observed ?: return
        val counts = snapshot.counters
        observed.completed(
            outcome,
            ObservedUsage(
                input = counts[PerfKeys.IN_TOKENS] ?: 0L,
                output = counts[PerfKeys.OUT_TOKENS] ?: 0L,
                cacheRead = counts[PerfKeys.CACHED_TOKENS] ?: 0L,
                cacheCreation = counts[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L,
            ),
            snapshot.marks[PerfKeys.TOTAL] ?: 0L,
            stopReason,
        )
    }
}
