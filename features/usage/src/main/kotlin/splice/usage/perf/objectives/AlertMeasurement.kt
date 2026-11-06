package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Detached scalar reduction of a leased query reply. JSON strings, not query subtrees, cross the boundary. */
internal data class AlertMeasurement(
    val observed: Double?,
    val count: Long,
    val fraction: Double,
    val coverage: String,
    val identity: String,
    val reason: String?,
    val identityTruncated: Boolean = false,
)

internal object AlertMeasurements {
    fun read(rule: AlertRule, result: JsonObject, nowMs: Long): AlertMeasurement {
        val measurement = when {
            rule.metric.startsWith("goodput.") -> goodput(rule, result)
            rule.metric.contains('.') && !rule.metric.startsWith("telemetry.") -> path(rule, result)
            else -> AlertDerivedMetrics.read(rule, result, nowMs)
        }
        return measurement.copy(
            identity = identities(result),
            reason = problem(rule, result, measurement),
            identityTruncated = ((result["identity_cohorts"] as? JsonArray)?.size ?: 0) > MAX_IDENTITIES,
        )
    }

    private fun problem(rule: AlertRule, result: JsonObject, measurement: AlertMeasurement): String? = when {
        ObjectiveJson.text(result, "status") in setOf("error", "unsupported", "busy") -> "query_unavailable"
        measurement.reason != null -> measurement.reason
        measurement.observed == null -> "metric_unavailable"
        measurement.count < rule.minCount -> "minimum_count"
        measurement.fraction < rule.minCoverage -> "minimum_coverage"
        else -> null
    }

    fun measured(
        value: Double?,
        count: Long,
        coverage: JsonObject,
        window: Long,
        reason: String? = null,
    ): AlertMeasurement {
        val fraction = ((ObjectiveJson.number(coverage, OBSERVED_MS) ?: 0.0) / window).coerceIn(0.0, 1.0)
        val reduced = buildJsonObject {
            put("fraction", fraction)
            put(OBSERVED_MS, ObjectiveJson.number(ObjectiveJson.number(coverage, OBSERVED_MS)))
            put("complete", ObjectiveJson.text(coverage, "complete") == "true")
            put(POPULATION, ObjectiveJson.text(ObjectiveJson.text(coverage, POPULATION)))
            put(APPROXIMATION, ObjectiveJson.text(ObjectiveJson.text(coverage, APPROXIMATION)))
            put("temporal_basis", ObjectiveJson.text(coverage, "temporal_basis") ?: "interval")
            put("sampled_at_ms", ObjectiveJson.number(ObjectiveJson.long(coverage, "sampled_at_ms")))
        }
        return AlertMeasurement(value, count, fraction, reduced.toString(), "[]", reason)
    }

    private fun goodput(rule: AlertRule, result: JsonObject): AlertMeasurement {
        val values = ObjectiveJson.obj(result, "goodput")
        val coverage = ObjectiveJson.obj(result, "population_coverage", REQUEST_POPULATION)
        return measured(
            ObjectiveJson.number(values, rule.metric.substringAfter('.')),
            ObjectiveJson.long(values, "known_evaluable") ?: 0,
            JsonObject(coverage + (POPULATION to ObjectiveJson.text(REQUEST_POPULATION))),
            rule.windowMs,
            "exact_goodput_unavailable".takeIf { ObjectiveJson.text(values, "population_complete") != "true" },
        )
    }

    private fun path(rule: AlertRule, result: JsonObject): AlertMeasurement {
        val parts = rule.metric.split('.')
        val values = ObjectiveJson.obj(result, parts[0], parts[1])
        val coverage = JsonObject(
            ObjectiveJson.obj(values, "coverage") +
                (POPULATION to ObjectiveJson.text(ObjectiveJson.text(values, "filter_attribution"))),
        )
        val selectedCoverage = if (parts[0] == "gauges") {
            gaugeCoverage(values, coverage, rule.windowMs)
        } else {
            JsonObject(
                coverage + (APPROXIMATION to ObjectiveJson.text(ObjectiveJson.text(values, APPROXIMATION))),
            )
        }
        val countKey = if (parts[0] == "gauges") "sample_count" else "count"
        val reason = "exact_counter_rate_unavailable".takeIf {
            parts[0] == "counters" && ObjectiveJson.text(selectedCoverage, "complete") != "true"
        }
        return measured(
            ObjectiveJson.number(values, parts[2]),
            ObjectiveJson.long(values, countKey) ?: 0,
            selectedCoverage,
            rule.windowMs,
            reason,
        )
    }

    private fun gaugeCoverage(values: JsonObject, coverage: JsonObject, window: Long): JsonObject {
        val scopes = ObjectiveJson.long(values, "scope_count")?.takeIf { it > 0 }
        val observed = if (scopes == null) 0.0 else (ObjectiveJson.number(values, "covered_ms") ?: 0.0) / scopes
        return JsonObject(
            coverage + mapOf(
                OBSERVED_MS to ObjectiveJson.number(
                    minOf(observed, ObjectiveJson.number(coverage, OBSERVED_MS) ?: 0.0, window.toDouble()),
                ),
                POPULATION to ObjectiveJson.text("gauge_time_per_scope"),
            ),
        )
    }

    private fun identities(result: JsonObject): String {
        val identities = result["identity_cohorts"] as? JsonArray ?: return "[]"
        return JsonArray(
            identities.take(MAX_IDENTITIES).mapNotNull { item ->
                val identity = item as? JsonObject ?: return@mapNotNull null
                JsonObject(
                    identityKeys.associateWith {
                        ObjectiveJson.text(
                            ObjectiveJson.text(identity, it)?.take(MAX_IDENTITY_LENGTH),
                        )
                    },
                )
            },
        ).toString()
    }
}

internal const val REQUEST_POPULATION = "request_scope_resolved_at_terminal_or_latest_checkpoint"
private const val MAX_IDENTITIES = 8
private const val OBSERVED_MS = "observed_ms"
private const val POPULATION = "population"
private const val APPROXIMATION = "approximation"
private const val MAX_IDENTITY_LENGTH = 128
private val identityKeys = setOf("boot_id", "build", "version", "boot_config_revision", "effective_config_revision")
