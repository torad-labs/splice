package splice.upstream.retry

import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope

/** Per-head gate samples contain counted permits, never borrowed continuation handles. */
public data class GateObservation(val recorder: PerfRecorder, val head: String) {
    internal fun record(state: GateReading) {
        recorder.emit(
            PerfScope(head = head),
            PerfEventKind.GATE_STATE,
            mapOf(
                "gate_permits" to state.permits.toDouble(),
                "gate_queued" to state.queued.toDouble(),
                "gate_limit" to state.limit.toDouble(),
                "gate_saturated" to if (state.limit > 0 && state.permits >= state.limit) 1.0 else 0.0,
                "gate_oldest_wait_ms" to state.oldestMs.coerceAtLeast(0).toDouble(),
                "gate_refused" to state.refused.toDouble(),
                "gate_queued_cancelled" to state.cancelled.toDouble(),
            ),
        )
    }
}

internal data class GateReading(
    val permits: Int,
    val queued: Int,
    val limit: Int,
    val oldestMs: Long,
    val refused: Long,
    val cancelled: Long,
)

internal fun interface GateReadingSource {
    fun read(): GateReading
}

internal class GateObservationState(private val source: GateReadingSource) {
    private var observation: GateObservation? = null

    fun install(observer: GateObservation) {
        check(observation == null) { "gate observation is installed once before publication" }
        observation = observer
    }

    fun record() {
        observation?.record(source.read())
    }
}
