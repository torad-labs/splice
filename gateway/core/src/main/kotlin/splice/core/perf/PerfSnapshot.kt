// NEW: the immutable perf view and the nullable-perf timing wrapper. Split
// from TurnPerf.kt so the recorder is not billed for the snapshot/render
// (concentration, 2026-08-19).
package splice.core.perf

/** Immutable view of a turn's recorded telemetry. */
public data class PerfSnapshot(
    val marks: Map<String, Long>,
    val counters: Map<String, Long>,
) {
    /**
     * The one-line perf summary: marks in pipeline order, then counters, skipping absent fields.
     * `[codex] perf outcome=ok compact=false model=m recv=3 ... | auth_ms=1 ... out_tokens=850`
     *
     * A member since the 2026-08-16 style migration (HD-M8): it reads nothing but this snapshot, so
     * the former trailing `snap` parameter became the receiver and the call site gained one.
     */
    public fun perfLine(head: String, outcome: String, compact: Boolean, model: String): String {
        val markPart = PerfKeys.markOrder
            .mapNotNull { k -> marks[k]?.let { "$k=$it" } }
            .joinToString(" ")
        val counterPart = counters.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return buildString {
            append("[").append(head).append("] perf outcome=").append(outcome)
            append(" compact=").append(compact)
            append(" model=").append(model)
            if (markPart.isNotEmpty()) append(" ").append(markPart)
            if (counterPart.isNotEmpty()) append(" | ").append(counterPart)
            append("\n")
        }
    }
}

/** The NULLABLE-perf timing wrapper. A named object since the 2026-08-16 style migration (HD-M8):
 *  the receiver is `TurnPerf?`, and Kotlin has no member function on a nullable type — so the
 *  receiver became the first argument (`perf.timedOr(k) { … }` reads
 *  `TurnPerfTiming.timedOr(perf, k) { … }`) rather than the call sites growing a null branch. */
public object TurnPerfTiming {

    /** Time a suspending [block] into [counter] when [perf] is wired; run it plain otherwise. */
    public suspend fun <T> timedOr(perf: TurnPerf?, counter: String, block: TimedWork<T>): T =
        if (perf == null) block() else perf.timed(counter, block)
}
