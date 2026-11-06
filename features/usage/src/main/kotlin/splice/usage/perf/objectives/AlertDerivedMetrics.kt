package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags

internal object AlertDerivedMetrics {
    fun read(rule: AlertRule, result: JsonObject, nowMs: Long): AlertMeasurement = when (rule.metric) {
        "failure_fraction", "refusal_fraction" -> outcomes(rule, result)
        "retry_amplification" -> retries(rule, result)
        "telemetry.staleness_ms" -> staleness(rule, result, nowMs)
        // Sink totals are lifetime cumulative samples. Never invent a window delta from one reading.
        "telemetry.loss_fraction" -> unavailable(rule, "sink_totals_lack_window_delta")
        else -> unavailable(rule, "metric_unavailable")
    }

    private fun outcomes(rule: AlertRule, result: JsonObject): AlertMeasurement {
        val outcomes = ObjectiveJson.obj(result, "outcomes")
        val known = outcomes.keys.filter {
            it !in setOf(
                "unknown",
                "?",
            )
        }.sumOf { ObjectiveJson.number(outcomes, it) ?: 0.0 }
        val unknown = (ObjectiveJson.number(outcomes, "unknown") ?: 0.0) + (ObjectiveJson.number(outcomes, "?") ?: 0.0)
        val numerator = numerator(rule.metric, outcomes)
        val coverage = ObjectiveJson.obj(result, "counters", "requests_finished", "coverage")
        val reason = when {
            ObjectiveJson.text(coverage, "complete") != "true" -> "outcome_population_incomplete"
            unknown > 0 -> "unknown_terminal_outcomes"
            else -> null
        }
        return AlertMeasurements.measured(
            numerator.takeIf {
                known > 0
            }?.div(known),
            known.toLong(),
            coverage,
            rule.windowMs,
            reason,
        )
    }

    private fun numerator(metric: String, outcomes: JsonObject): Double = if (metric == "refusal_fraction") {
        (
            ObjectiveJson.number(
                outcomes,
                "refused",
            ) ?: 0.0
            ) + (ObjectiveJson.number(outcomes, OutcomeTag.AT_CAPACITY.wire) ?: 0.0)
    } else {
        outcomes.keys.filter { it !in setOf("unknown", "?", "good") && !OutcomeTags.isClean(it) }
            .sumOf { ObjectiveJson.number(outcomes, it) ?: 0.0 }
    }

    private fun retries(rule: AlertRule, result: JsonObject): AlertMeasurement {
        val counters = ObjectiveJson.obj(result, "counters")
        val attempts = ObjectiveJson.number(ObjectiveJson.obj(counters, "attempts_started"), "sum")
        val arrivals = ObjectiveJson.number(ObjectiveJson.obj(counters, "requests_arrived"), "sum")
        val coverage = ObjectiveJson.obj(counters, "attempts_started", "coverage")
        val reason = "retry_population_incomplete".takeIf { ObjectiveJson.text(coverage, "complete") != "true" }
        return AlertMeasurements.measured(
            if (arrivals != null && arrivals > 0) attempts?.div(arrivals) else null,
            arrivals?.toLong() ?: 0,
            coverage,
            rule.windowMs,
            reason,
        )
    }

    private fun staleness(rule: AlertRule, result: JsonObject, nowMs: Long): AlertMeasurement {
        val sinks = (
            ObjectiveJson.obj(
                result,
                "coverage",
            )["sinks"] as? JsonArray
            ).orEmpty().mapNotNull { it as? JsonObject }
        val successes = sinks.mapNotNull { ObjectiveJson.long(it, "lastSuccessEpochMs") }.filter { it in 0..nowMs }
        val coverage = buildJsonObject {
            put("observed_ms", rule.windowMs)
            put("complete", true)
            put("population", "current_sink_status_snapshots")
        }
        return AlertMeasurements.measured(
            successes.minOrNull()?.let { (nowMs - it).toDouble() },
            successes.size.toLong(),
            coverage,
            rule.windowMs,
            "sink_freshness_unknown".takeIf { successes.isEmpty() || successes.size != sinks.size },
        )
    }

    private fun unavailable(rule: AlertRule, reason: String): AlertMeasurement =
        AlertMeasurements.measured(null, 0, JsonObject(emptyMap()), rule.windowMs, reason)
}
