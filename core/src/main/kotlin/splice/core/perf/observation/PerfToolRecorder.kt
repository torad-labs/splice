package splice.core.perf.observation

import java.util.concurrent.atomic.AtomicBoolean

/** A tool owns its terminal independently of the request, including callbacks arriving after delivery. */
public class PerfToolRecorder internal constructor(
    private val recorder: PerfRecorder,
    private val scope: PerfScope,
    public val links: PerfLinks,
    private val startedAt: Long,
    private val nativeSpan: Boolean = false,
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
            kind == PerfEventKind.TOOL_PHASE -> recorder.emit(scope, kind, ownership(values), links, outcome)
            else -> false
        }
    }

    public fun finish(outcome: String, values: Map<String, Double> = emptyMap()): Boolean = synchronized(lock) {
        if (finished) return false
        finished = true
        recorder.emit(
            scope,
            PerfEventKind.TOOL_FINISHED,
            terminalValues(values),
            links,
            outcome,
        )
    }

    /** Captures terminal timing before an external finalizer; the caller must publish the returned completion. */
    public fun prepareFinish(outcome: String, values: Map<String, Double> = emptyMap()): PerfToolCompletion? =
        synchronized(lock) {
            if (finished) return null
            val terminalValues = terminalValues(values)
            val observation = PerfObservation(
                recorder.stamp(),
                scope,
                PerfEventKind.TOOL_FINISHED,
                terminalValues,
                links,
                outcome,
            )
            val completion = PerfToolCompletion(recorder, observation)
            finished = true
            completion
        }

    private fun terminalValues(values: Map<String, Double>): Map<String, Double> =
        ownership(values) + ("tool_duration_ms" to recorder.elapsedSince(startedAt).toDouble())

    private fun ownership(values: Map<String, Double>): Map<String, Double> =
        if (nativeSpan) values + ("tool_native_span" to 1.0) else values
}

/** A single prepared tool terminal, retaining its measured time until external finalization has completed. */
public class PerfToolCompletion internal constructor(
    private val recorder: PerfRecorder,
    private val observation: PerfObservation,
) {
    private val published = AtomicBoolean()

    /** Publishes once, including when the external finalizer failed; observer refusal remains a false result. */
    public fun publish(): Boolean = published.compareAndSet(false, true) && recorder.publish(observation)
}
