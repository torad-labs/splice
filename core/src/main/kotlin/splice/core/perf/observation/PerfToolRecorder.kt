package splice.core.perf.observation

/** A tool owns its terminal independently of the request, including callbacks arriving after delivery. */
public class PerfToolRecorder internal constructor(
    private val recorder: PerfRecorder,
    private val scope: PerfScope,
    public val links: PerfLinks,
    private val startedAt: Long,
) {
    private val lock = Any()
    private var finished = false

    public fun observe(
        kind: PerfEventKind,
        values: Map<String, Double> = emptyMap(),
        outcome: String? = null,
    ): Boolean = synchronized(lock) {
        when {
            finished -> false
            kind == PerfEventKind.TOOL_FINISHED -> finish(outcome ?: "?", values)
            kind == PerfEventKind.TOOL_PHASE -> recorder.emit(scope, kind, values, links, outcome)
            else -> false
        }
    }

    public fun finish(outcome: String, values: Map<String, Double> = emptyMap()): Boolean = synchronized(lock) {
        if (finished) return false
        finished = true
        recorder.emit(
            scope,
            PerfEventKind.TOOL_FINISHED,
            values + ("tool_duration_ms" to recorder.elapsedSince(startedAt).toDouble()),
            links,
            outcome,
        )
    }
}
