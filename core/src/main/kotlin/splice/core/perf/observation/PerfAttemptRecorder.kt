package splice.core.perf.observation

/** An independently closing upstream attempt; the client request may finish while this handle is still live. */
public class PerfAttemptRecorder internal constructor(
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
            kind == PerfEventKind.ATTEMPT_FINISHED -> finish(outcome ?: "?", values)
            kind == PerfEventKind.PROTOCOL_PROGRESS || kind == PerfEventKind.GAP ->
                recorder.emit(scope, kind, values, links, outcome)
            else -> false
        }
    }

    public fun finish(outcome: String, values: Map<String, Double> = emptyMap()): Boolean = synchronized(lock) {
        if (finished) return false
        finished = true
        recorder.emit(
            scope,
            PerfEventKind.ATTEMPT_FINISHED,
            values + ("attempt_duration_ms" to recorder.elapsedSince(startedAt).toDouble()),
            links,
            outcome,
        )
    }
}
