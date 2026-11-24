// NEW: terminal launch profiles (issue #791, comment §4). A profile's `settings.json`: the person's own, with only the
// hooks and plugins the profile keeps. Hooks come from the settings file's own `hooks` table (keep_hooks "own" and
// "all") or none ("none"), less every `drop_hooks` match; a plugin's own hooks never ride here, they ship inside the
// plugin (ProfilePlugins). Every plugin the person enabled that the profile does not keep is written disabled, so
// nothing loads it. A variable the door sets or takes out is dropped from `env`: splice's standing door values win.
package splice.client.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.client.Keys
import splice.core.topology.KEEP_HOOKS_NONE
import splice.core.topology.LaunchProfileConfig
import splice.core.util.JsonScalars

internal class ProfileSettings(private val profile: LaunchProfileConfig, private val doorOwned: Set<String>) {
    /** [settings], the person's settings file, as the profile carries it. */
    fun filtered(settings: JsonObject): JsonObject {
        val out = settings.toMutableMap()
        val hooks = (settings[Keys.HOOKS] as? JsonObject)?.let(::hooks)
        if (hooks.isNullOrEmpty()) out.remove(Keys.HOOKS) else out[Keys.HOOKS] = hooks
        (settings[ENABLED_PLUGINS_KEY] as? JsonObject)?.let { out[ENABLED_PLUGINS_KEY] = plugins(it) }
        val env = settings[Keys.ENV] as? JsonObject
        if (env != null) out[Keys.ENV] = JsonObject(env.filterKeys { it !in doorOwned })
        return JsonObject(out)
    }

    /** The plugins the person enabled that the profile keeps, by id. */
    fun keptPlugins(settings: JsonObject): Set<String> =
        (settings[ENABLED_PLUGINS_KEY] as? JsonObject).orEmpty()
            .filter { (id, on) -> (on as? JsonPrimitive)?.booleanOrNull == true && keeps(id) }.keys

    /** Whether the profile keeps plugin [id] (`name@marketplace`), named whole or by its name. */
    fun keeps(id: String): Boolean = profile.keepPlugins.any { it == id || it == id.substringBefore(PLUGIN_AT) }

    private fun plugins(enabled: JsonObject): JsonObject = JsonObject(
        enabled.mapValues { (id, on) -> JsonPrimitive((on as? JsonPrimitive)?.booleanOrNull == true && keeps(id)) },
    )

    /** The hooks table with the profile's drops taken out, and what is left empty removed. */
    private fun hooks(table: JsonObject): JsonObject? {
        if (profile.keepHooks == KEEP_HOOKS_NONE) return null
        val kept = table.filterKeys { it !in profile.dropHooks }.mapNotNull { (event, groups) ->
            val left = (groups as? JsonArray)?.mapNotNull(::group) ?: return@mapNotNull event to groups
            (event to JsonArray(left)).takeIf { left.isNotEmpty() }
        }
        return JsonObject(kept.toMap())
    }

    /** One matcher group with every hook whose command holds a dropped word taken out; null once it holds none. */
    private fun group(element: JsonElement): JsonElement? {
        val group = element as? JsonObject ?: return element
        val hooks = group[Keys.HOOKS] as? JsonArray ?: return group
        val left = hooks.filterNot { hook ->
            val command = JsonScalars.str(hook as? JsonObject, HOOK_COMMAND).orEmpty()
            profile.dropHooks.any { it.isNotBlank() && command.contains(it) }
        }
        return if (left.isEmpty()) null else JsonObject(group + (Keys.HOOKS to JsonArray(left)))
    }
}

/** Claude Code's settings keys a profile filters. */
internal const val ENABLED_PLUGINS_KEY: String = "enabledPlugins"
private const val HOOK_COMMAND = "command"

// A plugin's id is its name, `@`, and the marketplace it came from.
internal const val PLUGIN_AT: Char = '@'
