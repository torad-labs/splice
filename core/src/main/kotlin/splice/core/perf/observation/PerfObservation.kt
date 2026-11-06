package splice.core.perf.observation

import kotlinx.serialization.Serializable

@Serializable
public enum class PerfEventKind {
    REQUEST_ARRIVED, REQUEST_ADMITTED, REQUEST_FINISHED, ATTEMPT_STARTED, ATTEMPT_FINISHED,
    PROTOCOL_PROGRESS, CONTENT_PROGRESS, CLIENT_PROGRESS, GAP, GATE_STATE, TOOL_STARTED,
    TOOL_FINISHED, TOOL_PHASE, RUNTIME, SELF_COST, CHECKPOINT, USAGE_RECORDED,
}

@Serializable
public data class PerfObservation(
    val stamp: PerfStamp,
    val scope: PerfScope,
    val kind: PerfEventKind,
    val values: Map<String, Double> = emptyMap(),
    val links: PerfLinks = PerfLinks(),
    val outcome: String? = null,
)

/** Return false when not accepted; implementations must account for loss without throwing into inference. */
public fun interface PerfObserver {
    public fun record(observation: PerfObservation): Boolean
}

public object NoPerfObserver : PerfObserver {
    override fun record(observation: PerfObservation): Boolean = true
}
