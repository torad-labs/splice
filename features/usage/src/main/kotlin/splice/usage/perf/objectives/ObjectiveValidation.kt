package splice.usage.perf.objectives

import kotlinx.serialization.encodeToString
import splice.usage.perf.query.PerfQueryParser

internal object ObjectiveValidation {
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    private val cohortKeys = setOf("head", "model", "transport", "outcome", "local", "compact", "streaming")

    fun problem(value: ObjectiveSettings): String? = when {
        value.schemaVersion != 1 -> "unsupported_schema_version"
        value.objectives.size > MAX_OBJECTIVE_ENTRIES || value.rules.size > MAX_OBJECTIVE_ENTRIES ->
            "settings_count_limit"
        value.objectives.distinctBy { it.id }.size != value.objectives.size -> "duplicate_objective"
        value.rules.distinctBy { it.id }.size != value.rules.size -> "duplicate_rule"
        else -> value.objectives.firstNotNullOfOrNull(::objectiveProblem)
            ?: value.rules.firstNotNullOfOrNull { ruleProblem(it, value) }
    }

    fun canonical(value: ObjectiveSettings): ObjectiveSettings {
        val objectives = value.objectives.sortedBy { it.id }.map {
            val normalized = it.copy(revision = "", cohort = normalizeCohort(it.cohort))
            normalized.copy(revision = ObjectiveJson.hash(ObjectiveJson.codec.encodeToString(normalized)))
        }
        val rules = value.rules.sortedBy { it.id }.map { it.copy(cohort = normalizeCohort(it.cohort)) }
        val normalized = value.copy(revision = "", objectives = objectives, rules = rules)
        return normalized.copy(revision = ObjectiveJson.hash(ObjectiveJson.codec.encodeToString(normalized)))
    }

    private fun objectiveProblem(value: ObjectiveDefinition): String? {
        val limits = listOf(value.firstContentMs, value.completionMs, value.queueMs, value.progressGapMs)
        return when {
            !identifier.matches(value.id) -> "invalid_objective_id"
            limits.all { it == null } -> "objective_requires_threshold"
            limits.filterNotNull().any { !it.isFinite() || it !in 0.0..MAX_RULE_DURATION_MS.toDouble() } ->
                "invalid_objective_threshold"
            else -> cohortProblem(value.cohort)
        }
    }

    private fun ruleProblem(value: AlertRule, settings: ObjectiveSettings): String? = when {
        !identifier.matches(value.id) -> "invalid_rule_id"
        !AlertMetricCatalog.supported(value.metric) -> "unsupported_rule_metric"
        !value.threshold.isFinite() || value.threshold < 0 -> "invalid_rule_threshold"
        value.comparison !in setOf("above", "below") -> "invalid_comparison"
        else -> detailsProblem(value, settings)
    }

    private fun detailsProblem(value: AlertRule, settings: ObjectiveSettings): String? =
        timingProblem(
            value,
        ) ?: recoveryProblem(value) ?: referenceProblem(value, settings) ?: snapshotProblem(value)
            ?: cohortProblem(value.cohort)

    private fun snapshotProblem(value: AlertRule): String? =
        "instant_snapshot_requires_zero_min_coverage".takeIf {
            value.metric == "telemetry.staleness_ms" && value.minCoverage != 0.0
        }

    private fun timingProblem(value: AlertRule): String? = when {
        value.windowMs !in MIN_WINDOW..MAX_RULE_DURATION_MS -> "invalid_rule_window"
        value.minCount !in 1..MAX_COUNT -> "invalid_rule_count"
        !value.minCoverage.isFinite() || value.minCoverage !in 0.0..1.0 -> "invalid_rule_coverage"
        value.holdMs !in 0..MAX_RULE_DURATION_MS || value.cooldownMs !in 0..MAX_RULE_DURATION_MS ->
            "invalid_rule_duration"
        else -> null
    }

    private fun recoveryProblem(value: AlertRule): String? = when {
        !value.recovery.threshold.isFinite() || value.recovery.threshold < 0 -> "invalid_recovery_threshold"
        value.recovery.holdMs !in 0..MAX_RULE_DURATION_MS -> "invalid_recovery_hold"
        value.comparison == "above" && value.recovery.threshold > value.threshold -> "invalid_hysteresis"
        value.comparison == "below" && value.recovery.threshold < value.threshold -> "invalid_hysteresis"
        else -> null
    }

    private fun referenceProblem(value: AlertRule, settings: ObjectiveSettings): String? {
        val objective = settings.objectives.find { it.id == value.objectiveId }
        val filters = objective?.cohort.orEmpty() + value.cohort
        return when {
            value.objectiveId != null && objective == null -> "unknown_rule_objective"
            value.metric.startsWith("goodput.") && objective == null -> "goodput_requires_objective"
            conflicts(objective, value) ->
                "objective_cohort_conflict"
            value.metric.startsWith(
                "telemetry.",
            ) && filters.keys.any { it != "head" } -> "sink_status_cohort_unavailable"
            else -> null
        }
    }

    private fun conflicts(objective: ObjectiveDefinition?, rule: AlertRule): Boolean {
        val expected = normalizeCohort(objective?.cohort.orEmpty())
        val selected = normalizeCohort(rule.cohort)
        return expected.any { (key, value) -> selected[key]?.let { it != value } == true }
    }

    private fun normalizeCohort(cohort: Map<String, String>): Map<String, String> = cohort.toSortedMap().mapValues {
        if (it.key in setOf("local", "compact", "streaming")) (it.value in setOf("1", "true")).toString() else it.value
    }

    private fun cohortProblem(value: Map<String, String>): String? = when {
        value.keys.any { it !in cohortKeys } -> "unsupported_cohort_dimension"
        value.values.any { it.length > MAX_COHORT_VALUE } -> "cohort_value_limit"
        PerfQueryParser.parse(value + mapOf("from" to "0", "until" to "60000"), MIN_WINDOW).error != null ->
            "invalid_cohort"
        else -> null
    }
}

// Allow thirty-two objectives and thirty-two rules so settings, lifecycle state and outbox work stay finite.
private const val MAX_OBJECTIVE_ENTRIES = 32

// Bound cohort selector values to 128 characters before storing objective or rule settings.
private const val MAX_COHORT_VALUE = 128

// Reject minimum-count settings above one million rather than accepting arbitrarily large evaluation thresholds.
private const val MAX_COUNT = 1_000_000L

// Require at least one second of alert evidence to avoid zero-width or subsecond rule windows.
private const val MIN_WINDOW = 1_000L

// Cap rule windows, holds, cooldowns and latency objectives at seven days to bound persisted timing policy.
private const val MAX_RULE_DURATION_MS = 604_800_000L
