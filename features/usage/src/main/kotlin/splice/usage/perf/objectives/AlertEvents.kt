package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AlertEvents {
    fun render(
        state: AlertState,
        rule: AlertRule,
        context: AlertContext,
        measurement: AlertMeasurement,
        now: Long,
    ): JsonObject = buildJsonObject {
        val alertId = state.alertId ?: ObjectiveJson.hash("${state.revision}:${state.pendingSince ?: now}")
        put("schema_version", 1)
        put("alert_id", alertId)
        put("dedup_key", ObjectiveJson.hash("$alertId:${state.state}"))
        put("rule_id", rule.id)
        put("rule_revision", state.revision)
        put("settings_revision", context.settingsRevision)
        put("state", state.state)
        put("active", state.active)
        put("observed", ObjectiveJson.number(measurement.observed))
        put("threshold", rule.threshold)
        put("metric", rule.metric)
        put("comparison", rule.comparison)
        put("sample_count", measurement.count)
        timing(measurement, now).forEach { (key, value) -> put(key, value) }
        put("cohort", JsonObject(context.cohort.mapValues { JsonPrimitive(it.value) }))
        put("coverage", ObjectiveJson.codec.parseToJsonElement(measurement.coverage))
        put("identity", ObjectiveJson.codec.parseToJsonElement(measurement.identity.encoded))
        put("identity_truncated", measurement.identity.truncated)
        put("objective_revision", ObjectiveJson.text(context.objective?.revision))
        put("pending_since_ms", ObjectiveJson.number(state.pendingSince))
        put("firing_since_ms", ObjectiveJson.number(state.firingSince))
        put("resolved_at_ms", ObjectiveJson.number(state.resolvedAt))
        put("recovery_since_ms", ObjectiveJson.number(state.recoverySince))
        put("hold_observed_ms", state.heldMs)
        put("recovery_observed_ms", state.recoveredMs)
        put("as_of_ms", now)
        put("reason", ObjectiveJson.text(state.reason))
        put("continuity", if (context.restarted) "restart_gap_hold_reset" else "sampled_windows")
        put("delivery_enabled", rule.deliveryEnabled)
        put("delivery_status", if (rule.deliveryEnabled && state.state == "firing") "pending" else "disabled")
        put("diagnostic_links", links(context, measurement))
    }

    private fun timing(measurement: AlertMeasurement, now: Long): JsonObject = buildJsonObject {
        put("window", window(measurement))
        put("evaluated_at_ms", now)
        put("data_as_of_ms", ObjectiveJson.number(measurement.timing?.dataAsOf))
        put("data_lag_ms", ObjectiveJson.number(measurement.timing?.dataAsOf?.let { now - it }))
        put("window_basis", ObjectiveJson.text(measurement.timing?.window?.basis))
        put("window_lag_ms", ObjectiveJson.number(measurement.timing?.window?.until?.let { now - it }))
        put("settled_until_ms", ObjectiveJson.number(measurement.timing?.window?.horizon))
        put("max_window_lag_ms", ObjectiveJson.number(measurement.timing?.window?.maxLagMs))
    }

    private fun links(context: AlertContext, measurement: AlertMeasurement): JsonArray {
        val window = measurement.timing?.window
        return if (window?.from == null || window.until == null) {
            JsonArray(
                emptyList(),
            )
        } else {
            links(context, window.from, window.until)
        }
    }

    private fun window(measurement: AlertMeasurement): JsonObject = buildJsonObject {
        put("from_ms", ObjectiveJson.number(measurement.timing?.window?.from))
        put("until_ms", ObjectiveJson.number(measurement.timing?.window?.until))
    }

    private fun links(context: AlertContext, from: Long, until: Long): JsonArray = JsonArray(
        listOf(
            buildJsonObject {
                put("endpoint", "/api/perf/diagnose")
                put(
                    "parameters",
                    buildJsonObject {
                        put("from", from.toString())
                        put("until", until.toString())
                        put("contract", "1")
                        context.cohort.forEach { (key, value) -> put(key, value) }
                        context.objective?.let { put("objective", it.id) }
                    },
                )
            },
        ),
    )
}

internal data class AlertContext(
    val cohort: Map<String, String>,
    val objective: ObjectiveDefinition?,
    val restarted: Boolean,
    val settingsRevision: String,
)
