// Spec section 9: every knob shows its source scope, effective value and live-versus-restart disposition inline.
// One entry per Knob in the schema, so a knob the schema gains is in the answer without a second list to keep.
package splice.configuration.knobs

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.config.ConfigLayers
import splice.core.config.Knob
import splice.core.config.daemonWideKnobs

/** Where a knob's effective value comes from, lowest precedence first: the layers [splice.core.config.ConfigService]
 *  folds in that order, each later one beating the earlier. */
private const val VALUE = "value"

// "head" is last: a command's own value beats every shared layer (ConfigService.mergedRaw, Oct 12).
private val SCOPES_BY_PRECEDENCE = listOf("default", "toml", "file", "env", "runtime", "head")

/** Knobs the schema carries but no layer can change: ConfigCoercion answers them with one value whatever is written. */
private val PINNED_REASONS = mapOf(
    Knob.MIRROR_REASONING to "pinned false by project rule; ask Marcos to change",
)

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
                val scope = SCOPES_BY_PRECEDENCE.last { s -> knob.key in byScope.getValue(s) }
                put("scope", scope)
                // a value set in the shell names the variables it is read from, so the row can say which to change
                if (scope == "env") putJsonArray("env_names") { knob.envNames.forEach { add(it) } }
                put("disposition", if (knob.restartRequired) "restart" else "live")
                // what the value is, so the page builds its control from the daemon's own answer (gap 458)
                put("kind", knob.kind.name.lowercase())
                // whether one command may hold its own value: every knob a head reads per turn (HeadOverrideWrite)
                put("per_command", knob !in daemonWideKnobs)
                val readOnlyReason = PINNED_REASONS[knob]
                    ?: "set only in [heads.<key>.overrides], never by PATCH".takeIf { knob.headOnly }
                put("editable", readOnlyReason == null)
                if (readOnlyReason != null) put("read_only_reason", readOnlyReason)
            }
        }
    }
}
