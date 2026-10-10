// Spec section 9: every knob shows its source scope, effective value and live-versus-restart disposition inline.
// One entry per Knob in the schema, so a knob the schema gains is in the answer without a second list to keep.
package splice.configuration.knobs

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.config.ConfigLayers
import splice.core.config.Knob

/** Where a knob's effective value comes from, lowest precedence first: the layers [splice.core.config.ConfigService]
 *  folds in that order, each later one beating the earlier. */
private const val VALUE = "value"

private val SCOPES_BY_PRECEDENCE = listOf("default", "toml", "head", "file", "env", "runtime")

internal class KnobViews {
    /** [effective] is the merged value for [headKey] (or the global view); [layers] says which layer set each key. */
    fun of(effective: JsonObject, layers: ConfigLayers, headKey: String?): JsonObject = buildJsonObject {
        val byScope = mapOf(
            "default" to layers.defaults,
            "toml" to layers.headOverrides,
            "head" to (headKey?.let(layers.perHead::get).orEmpty()),
            "file" to layers.file,
            "env" to layers.env,
            "runtime" to layers.runtime,
        )
        Knob.entries.forEach { knob ->
            putJsonObject(knob.key) {
                put(VALUE, effective[knob.key] ?: JsonNull)
                put("scope", SCOPES_BY_PRECEDENCE.last { scope -> knob.key in byScope.getValue(scope) })
                put("disposition", if (knob.restartRequired) "restart" else "live")
                put("editable", !knob.headOnly)
                if (knob.headOnly) put("read_only_reason", "set only in [heads.<key>.overrides], never by PATCH")
            }
        }
    }
}
