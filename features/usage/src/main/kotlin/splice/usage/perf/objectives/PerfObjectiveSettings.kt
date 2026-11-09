package splice.usage.perf.objectives

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.perf.observation.PerfObjectiveSet

@Serializable
internal data class ObjectiveSettings(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val revision: String = "",
    val objectives: List<ObjectiveDefinition> = emptyList(),
    val rules: List<AlertRule> = emptyList(),
)

@Serializable
internal data class ObjectiveDefinition(
    val id: String,
    val revision: String = "",
    val cohort: Map<String, String> = emptyMap(),
    @SerialName("first_content_ms") val firstContentMs: Double? = null,
    @SerialName("completion_ms") val completionMs: Double? = null,
    @SerialName("queue_ms") val queueMs: Double? = null,
    @SerialName("progress_gap_ms") val progressGapMs: Double? = null,
) {
    fun objective(): PerfObjectiveSet =
        PerfObjectiveSet(id, revision, firstContentMs, completionMs, queueMs, progressGapMs)
}

@Serializable
internal data class AlertRule(
    val id: String,
    @SerialName("objective_id") val objectiveId: String? = null,
    val cohort: Map<String, String> = emptyMap(),
    val metric: String,
    val threshold: Double,
    @SerialName("window_ms") val windowMs: Long,
    @SerialName("min_count") val minCount: Long,
    @SerialName("min_coverage") val minCoverage: Double,
    @SerialName("hold_ms") val holdMs: Long,
    @SerialName("cooldown_ms") val cooldownMs: Long,
    val recovery: AlertRecovery,
    @SerialName("delivery_enabled") val deliveryEnabled: Boolean = false,
    val comparison: String = "above",
) {
    fun breached(value: Double): Boolean = if (comparison == "above") value > threshold else value < threshold
    fun recovered(value: Double): Boolean =
        if (comparison == "above") value <= recovery.threshold else value >= recovery.threshold
}

@Serializable
internal data class AlertRecovery(val threshold: Double, @SerialName("hold_ms") val holdMs: Long = 0)
