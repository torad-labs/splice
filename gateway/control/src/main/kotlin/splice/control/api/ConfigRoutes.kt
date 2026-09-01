// PORT-OF: ControlServer.kt (configJson, patchConfig) + ScalarJson @ a77531a — invariants
// unchanged: the GET projection and the PATCH handler are one concern (splice.core.config) split
// across the routing side and the payload side today purely because ControlPayloads was at its
// function ceiling. ScalarJson the class is folded in as a private member — its only two call
// sites are now members of one class, so the "neither owns the other's copy" rationale is gone.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.config.ConfigService
import splice.core.config.restartRequiredKnobKeys

internal class ConfigRoutes(
    private val config: ConfigService,
    private val jsonBody: JsonBody,
    private val payloads: ControlPayloads,
) {
    fun configJson(headKey: String? = null): String {
        val layers = config.layers()
        // JW-06: ?head=<key> answers "why is THIS head's knob X" — effective folds the head's
        // override layer exactly as admission does; unknown/absent key stays the global view.
        val effective = config.getConfig(headKey).asMap()
        return buildJsonObject {
            put("effective", mapToJson(effective))
            headKey?.let { put("head", it) }
            putJsonObject("layers") {
                put("defaults", mapToJson(layers.defaults))
                // The operator-facing layer: ~/.config/splice/splice.toml [daemon]/[defaults].
                // Shown in precedence position (beats defaults, loses to file/env/runtime) so
                // "why is this knob X?" is answerable from the payload alone.
                put("toml", mapToJson(layers.headOverrides))
                // JW-06: [heads.<key>.overrides] — precedence directly above the global TOML
                // layer (mergedRaw's real order); only override-carrying heads appear.
                putJsonObject("perHead") {
                    layers.perHead.forEach { (key, knobs) -> put(key, mapToJson(knobs)) }
                }
                put("file", mapToJson(layers.file))
                put("env", mapToJson(layers.env))
                put("runtime", mapToJson(layers.runtime))
            }
            putJsonArray("restart_required_keys") { restartRequiredKnobKeys.forEach { add(it) } }
            put("source", "control")
        }.toString()
    }

    suspend fun patchConfig(call: ApplicationCall) {
        val partial = jsonBody.parse(call)
        if (partial == null) {
            call.respondText(
                payloads.errorJson("invalid body"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        // JsonNull must map to Kotlin null (= DELETE) — `(v as? JsonPrimitive)?.content` turned
        // it into the 4-char string "null" and PERSISTED it (audit 2026-07-18). Objects/arrays
        // are rejected outright instead of being silently stringified or deleted.
        val nonScalar = partial.filterValues { it !is JsonPrimitive }.keys
        val map = (partial - nonScalar).mapValues { (_, v) ->
            (v as JsonPrimitive).takeUnless { it is JsonNull }?.content
        }
        // NO per-head fanout needed (single JVM) — but NOTE: most knobs are snapshotted at
        // Daemon.start (restart-required); only the genuinely hot ones apply to the next request.
        val result = config.patch(map)
        call.respondText(
            buildJsonObject {
                put("applied", mapToJson(result.applied))
                putJsonObject("rejected") {
                    result.rejected.forEach { (k, v) -> put(k, v) }
                    nonScalar.forEach { put(it, "invalid value (must be a scalar or null)") }
                }
                putJsonArray("restart_required") { result.restartRequired.forEach { add(it) } }
                putJsonArray("targets") {} // no per-head fanout targets in single-daemon
                put("persisted", "state/config.json")
            }.toString(),
            ContentType.Application.Json,
        )
    }

    // The scalar-map -> JSON writer both configJson (the layer views) and patchConfig (the
    // applied/rejected result) need.
    private fun mapToJson(values: Map<String, Any?>) = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                null -> put(key, null as String?)
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
}
