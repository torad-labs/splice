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
        put("window", buildJsonObject { put("from_ms", now - rule.windowMs); put("until_ms", now) })
        put("cohort", JsonObject(context.cohort.mapValues { JsonPrimitive(it.value) }))
        put("coverage", ObjectiveJson.codec.parseToJsonElement(measurement.coverage))
        put("identity", ObjectiveJson.codec.parseToJsonElement(measurement.identity))
        put("identity_truncated", measurement.identityTruncated)
        put("objective_revision", ObjectiveJson.text(context.objective?.revision))
        put("pending_since_ms", ObjectiveJson.number(state.pendingSince))
        put("firing_since_ms", ObjectiveJson.number(state.firingSince))
        put("resolved_at_ms", ObjectiveJson.number(state.resolvedAt))
        put("recovery_since_ms", ObjectiveJson.number(state.recoverySince))
        put("as_of_ms", now)
        put("reason", ObjectiveJson.text(state.reason))
        put("continuity", if (context.restarted) "restart_gap_hold_reset" else "sampled_windows")
        put("delivery_enabled", rule.deliveryEnabled)
        put("delivery_status", if (rule.deliveryEnabled && state.state == "firing") "pending" else "disabled")
        put("diagnostic_links", links(context, now - rule.windowMs, now))
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
