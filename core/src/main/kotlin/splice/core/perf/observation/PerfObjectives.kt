package splice.core.perf.observation

import kotlinx.serialization.Serializable
import splice.core.perf.OutcomeTags

@Serializable
public data class PerfObjectiveSet(
    val id: String,
    val revision: String,
    val firstContentMs: Double? = null,
    val completionMs: Double? = null,
    val queueMs: Double? = null,
    val progressGapMs: Double? = null,
)

@Serializable
public enum class PerfObjectiveVerdict { GOOD, BAD, PENDING, UNKNOWN }

/** One evaluator for queries and alerts. An empty objective carries no universal latency promise. */
public object PerfObjectives {
    public fun evaluate(
        outcome: String?,
        measurements: Map<String, Double>,
        objective: PerfObjectiveSet,
    ): PerfObjectiveVerdict {
        if (outcome == null) return PerfObjectiveVerdict.PENDING
        val normalized = PerfDimensions.terminalOutcome(outcome)
        if (normalized == "?") return PerfObjectiveVerdict.UNKNOWN
        return if (!OutcomeTags.isClean(normalized)) PerfObjectiveVerdict.BAD else measured(measurements, objective)
    }

    private fun measured(values: Map<String, Double>, objective: PerfObjectiveSet): PerfObjectiveVerdict {
        val bounds = listOf(
            "arrival_to_first_meaningful_content_ms" to objective.firstContentMs,
            "arrival_to_terminal_ms" to objective.completionMs,
            "queue_wait_ms" to objective.queueMs,
            "upstream_gap_max_ms" to objective.progressGapMs,
        ).filter { it.second != null }
        if (bounds.isEmpty()) return PerfObjectiveVerdict.UNKNOWN
        val checks = bounds.map { (key, bound) -> compare(values[key], requireNotNull(bound)) }
        return when {
            PerfObjectiveVerdict.BAD in checks -> PerfObjectiveVerdict.BAD
            PerfObjectiveVerdict.UNKNOWN in checks -> PerfObjectiveVerdict.UNKNOWN
            else -> PerfObjectiveVerdict.GOOD
        }
    }

    private fun compare(value: Double?, bound: Double): PerfObjectiveVerdict = when {
        !bound.isFinite() || bound < 0 -> PerfObjectiveVerdict.UNKNOWN
        value == null || !value.isFinite() || value < 0 -> PerfObjectiveVerdict.UNKNOWN
        value > bound -> PerfObjectiveVerdict.BAD
        else -> PerfObjectiveVerdict.GOOD
    }
}
