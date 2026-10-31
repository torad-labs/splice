// NEW: the immutable perf view and the nullable-perf timing wrapper. Split
// from TurnPerf.kt so the recorder is not billed for the snapshot/render
// (concentration, 2026-08-19).
package splice.core.perf

/** Immutable view of a turn's recorded telemetry. */
public data class PerfSnapshot(
    val marks: Map<String, Long>,
    val counters: Map<String, Long>,
    val upstreamGapEnd: UpstreamGapEnd? = null,
)

/** The NULLABLE-perf timing wrapper. A named object since the 2026-08-16 style migration (HD-M8):
 *  the receiver is `TurnPerf?`, and Kotlin has no member function on a nullable type — so the
 *  receiver became the first argument (`perf.timedOr(k) { … }` reads
 *  `TurnPerfTiming.timedOr(perf, k) { … }`) rather than the call sites growing a null branch. */
public object TurnPerfTiming {

    /** Time a suspending [block] into [counter] when [perf] is wired; run it plain otherwise. */
    public suspend fun <T> timedOr(perf: TurnPerf?, counter: String, block: TimedWork<T>): T =
        if (perf == null) block() else perf.timed(counter, block)
}
