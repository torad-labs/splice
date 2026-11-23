// NEW: desktop-policy-console. splice's policy for Claude Desktop, layer by layer, lowest first: the default the build
// ships (DesktopPolicyDefaults), what the person set up in Claude Code laid over it (DesktopAppConfig.policy), and the
// values they set in the console (DesktopPolicyOverrides). What every configuration on the door holds over any policy
// (DesktopPolicy.standing) and the connection to the door sit above all three, and no override reaches them.
//
// A configuration splice made is written whole from these layers; one that points Desktop at the door but is the
// person's own is filled where it says nothing, and then given the console's values over what it says, since those
// are the person's newest choice. A value put back to its default is written back as the layers below hold it.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DesktopAppConfig
import splice.client.desktop.POLICY_IMPORT

internal class DesktopPolicyLayers(
    private val config: DesktopAppConfig,
    private val defaults: DesktopPolicyDefaults,
    /** The console's values; none when null. */
    val overrides: DesktopPolicyOverrides?,
) {
    /** The policy without the console's values: the build's default with the person's Claude Code laid over it. */
    fun base(): JsonObject = config.policy(defaults.read())

    /** [base] with the console's values ([held]) laid over it. */
    fun effective(base: JsonObject = base(), held: JsonObject = held()): JsonObject =
        JsonObject(base + over(base, held))

    /** [current], a configuration on the door already given splice's policy, with the console's values over what it
     *  says, and each key of [reset] (whose value the console no longer holds) as the layers below hold it, or gone
     *  where they hold none. */
    fun forced(current: JsonObject, reset: Set<String>): JsonObject {
        val base = base()
        val held = held()
        val back = reset - held.keys
        val restored = back.mapNotNull { key -> base[key]?.let { key to it } }.toMap()
        val dropped = back.filterNot { it in base }.toSet()
        return JsonObject(current + over(base, held) + restored - dropped)
    }

    private fun held(): JsonObject = overrides?.values() ?: JsonObject(emptyMap())

    /** [held] as it is laid over [base]: an import setting the console holds only part of keeps the rest of
     *  [base]'s. */
    private fun over(base: JsonObject, held: JsonObject): Map<String, JsonElement> = held.mapValues { (key, value) ->
        val under = (base[key] as? JsonObject)?.takeIf { key == POLICY_IMPORT }
        if (value is JsonObject && under != null) JsonObject(under + value) else value
    }
}
