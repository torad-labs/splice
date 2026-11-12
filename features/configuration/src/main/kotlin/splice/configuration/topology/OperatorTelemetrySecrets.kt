package splice.configuration.topology

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.topology.TopologyFinding

/** Header JSON is one secret field. The displayed mask preserves the exact stored JSON on an unrelated edit. */
internal object OperatorTelemetrySecrets {
    fun masked(tree: JsonObject): JsonObject = map(tree, null, "", null)

    fun unmasked(tree: JsonObject, stored: JsonObject, findings: MutableList<TopologyFinding>): JsonObject =
        map(tree, stored, "", findings)

    private fun map(
        tree: JsonObject,
        stored: JsonObject?,
        path: String,
        findings: MutableList<TopologyFinding>?,
    ): JsonObject =
        JsonObject(
            tree.mapValues { (key, value) ->
                val at = if (path.isEmpty()) key else "$path.$key"
                when {
                    key in SECRETS && findings == null -> JsonPrimitive(MASK)
                    key in SECRETS -> restore(stored?.get(key), at, value, findings)
                    value is JsonObject -> map(value, stored?.get(key) as? JsonObject, at, findings)
                    else -> value
                }
            },
        )

    private fun restore(
        saved: JsonElement?,
        path: String,
        value: JsonElement,
        findings: MutableList<TopologyFinding>?,
    ): JsonElement {
        if (value != JsonPrimitive(MASK)) return value
        if (saved == null) findings?.add(TopologyFinding(path, "masked value has no stored value; enter it explicitly"))
        return saved ?: value
    }
}

private val SECRETS = setOf("otlp_headers", "otlp_endpoint", "telemetryOtlpHeaders", "telemetryOtlpEndpoint")
