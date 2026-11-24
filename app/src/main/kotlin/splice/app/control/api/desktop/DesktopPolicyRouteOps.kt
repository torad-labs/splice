// NEW: desktop-policy-console. What [DesktopPolicyRoutes] reads and changes: a PATCH body checked key by key against
// the keys the console sets (DesktopPolicyCatalog), and the policy as Settings draws it, group by group, each key with
// its words, the values it takes, splice's default, the value Desktop is given and the console's own value. No
// value carries a credential: a marketplace address with one is never taken (DesktopPolicyKind.Marketplaces).
package splice.app.control.api.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.desktop.setup.DesktopPolicyLayers
import splice.app.desktop.setup.PolicyOverridesRead
import splice.client.desktop.DesktopPolicyCatalog
import splice.client.desktop.DesktopPolicyField
import splice.client.desktop.DesktopPolicyGroup
import splice.client.desktop.DesktopPolicyKind

/** A PATCH body read: the values to keep and the keys to put back to the default, or why it is refused. */
internal sealed class PolicyPatch {
    data class Changes(val set: Map<String, JsonElement>, val reset: Set<String>) : PolicyPatch()

    /** Each refused key with why; [why] says it in one line. */
    data class Refused(val why: String, val keys: Map<String, String>) : PolicyPatch()
}

internal class DesktopPolicyRouteOps(private val catalog: DesktopPolicyCatalog = DesktopPolicyCatalog()) {
    /** [body], each key a value to keep or null to put it back to the default. */
    fun parse(body: JsonObject): PolicyPatch {
        if (body.isEmpty()) return PolicyPatch.Refused("name at least one policy key", emptyMap())
        val refused = body.mapNotNull { (key, value) -> refusal(key, value)?.let { key to it } }.toMap()
        if (refused.isNotEmpty()) {
            val (key, why) = refused.entries.first()
            return PolicyPatch.Refused("$key: $why", refused)
        }
        val (reset, set) = body.entries.partition { it.value is JsonNull }
        return PolicyPatch.Changes(set.associate { it.key to it.value }, reset.mapTo(mutableSetOf()) { it.key })
    }

    /** The console's values once [patch] is made of [held]. */
    fun changed(held: JsonObject, patch: PolicyPatch.Changes): JsonObject = JsonObject(held + patch.set - patch.reset)

    /** The policy as Settings draws it, from [layers] and the overrides file as [read] holds it. */
    fun snapshot(layers: DesktopPolicyLayers, read: PolicyOverridesRead, restartRequired: Boolean): JsonObject {
        val held = (read as? PolicyOverridesRead.Held)?.values ?: JsonObject(emptyMap())
        val base = layers.base()
        val effective = layers.effective(base, held)
        val groups = DesktopPolicyGroup.entries.map { group ->
            val keys = catalog.fields.filter { it.group == group }
                .map { describe(it, Values(base[it.key], effective[it.key], held[it.key])) }
            buildJsonObject {
                put("id", group.wire)
                put("title", group.title)
                put("keys", JsonArray(keys))
            }
        }
        return buildJsonObject {
            put("groups", JsonArray(groups))
            put("locked", JsonArray(catalog.locked.map(::JsonPrimitive)))
            put("overrides_error", (read as? PolicyOverridesRead.Unreadable)?.why)
            put("ignored", JsonArray((read as? PolicyOverridesRead.Held)?.ignored.orEmpty().map(::JsonPrimitive)))
            put("restart_required", restartRequired)
        }
    }

    private fun refusal(key: String, value: JsonElement): String? {
        val field = catalog.field(key)
        return when {
            key in catalog.locked -> "set by splice itself; the console does not change it"
            field == null -> "not a Desktop policy key the console sets"
            value is JsonNull -> null
            else -> field.kind.refusal(value)
        }
    }

    private fun describe(field: DesktopPolicyField, values: Values): JsonObject = buildJsonObject {
        put("key", field.key)
        put("label", field.label)
        put("description", field.description)
        put("type", field.kind.wire)
        when (val kind = field.kind) {
            is DesktopPolicyKind.Choice -> put("options", JsonArray(kind.options.map(::JsonPrimitive)))
            is DesktopPolicyKind.WholeNumber -> {
                put("min", kind.min)
                put("max", kind.max)
            }
            DesktopPolicyKind.Switch,
            DesktopPolicyKind.Hosts,
            DesktopPolicyKind.Marketplaces,
            DesktopPolicyKind.Import,
            -> Unit
        }
        put("default", values.default ?: JsonNull)
        put("effective", values.effective ?: JsonNull)
        put("override", values.override ?: JsonNull)
        put("overridden", values.override != null)
    }

    /** One key's value in each layer: splice's default, what Desktop is given, and the console's own. */
    private data class Values(val default: JsonElement?, val effective: JsonElement?, val override: JsonElement?)
}
