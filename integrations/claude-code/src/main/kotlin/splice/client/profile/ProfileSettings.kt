// NEW: terminal launch profiles (issue #791, comment §4). A profile's `settings.json` and `settings.local.json`: the
// person's own, with only the hooks and plugins the profile keeps. Hooks come from the settings files' own `hooks`
// tables (keep_hooks "own" and "all") or none ("none"), less every `drop_hooks` match; a plugin's own hooks never ride
// here, they ship inside the plugin (ProfilePluginCache). A plugin is enabled only when the profile keeps it and its
// install is in the profile's cache; every other one the person enabled is written disabled, so nothing loads it. A
// variable the door sets or takes out is dropped from `env`: splice's standing door values win. No model list rides
// (AGENTS.md, "One list"): the gateway's /v1/models is the one list, so the settings keys and `env` variables that lay
// one are taken out.
package splice.client.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.client.ClaudeModelLists
import splice.client.Keys
import splice.core.topology.KEEP_HOOKS_NONE
import splice.core.topology.LaunchProfileConfig
import splice.core.util.JsonScalars

internal class ProfileSettings(
    private val profile: LaunchProfileConfig,
    private val doorOwned: Set<String>,
    /** Every settings file the profile is rebuilt from, so an event either names is an event in both. */
    sources: List<JsonObject>,
) {
    private val events = HOOK_EVENTS + sources.flatMap { (it[Keys.HOOKS] as? JsonObject)?.keys.orEmpty() }
    private val drops = profile.dropHooks.filter(String::isNotBlank)

    /** The drop_hooks entries that name an event, and drop its hooks, and the words that drop hooks by command. */
    private val eventDrops = drops.filter { it in events }.toSet()
    private val wordDrops = drops.filterNot { it in events }

    /** [settings], one of the person's settings files, as the profile carries it, with only the plugins [loaded]
     *  enabled. */
    fun filtered(settings: JsonObject, loaded: Set<String>): JsonObject {
        val out = settings.filterKeys { it !in ClaudeModelLists.settingsKeys }.toMutableMap()
        val hooks = (settings[Keys.HOOKS] as? JsonObject)?.let(::hooks)
        if (hooks.isNullOrEmpty()) out.remove(Keys.HOOKS) else out[Keys.HOOKS] = hooks
        (settings[ENABLED_PLUGINS_KEY] as? JsonObject)?.let { out[ENABLED_PLUGINS_KEY] = plugins(it, loaded) }
        val env = settings[Keys.ENV] as? JsonObject
        if (env != null) out[Keys.ENV] = JsonObject(env.filterKeys { it !in doorOwned && it !in modelVariables })
        return JsonObject(out)
    }

    /** The plugins the person enabled in [settings] that the profile keeps, by id. */
    fun keptPlugins(settings: JsonObject): Set<String> =
        (settings[ENABLED_PLUGINS_KEY] as? JsonObject).orEmpty()
            .filter { (id, on) -> (on as? JsonPrimitive)?.booleanOrNull == true && keeps(id) }.keys

    /** Whether the profile keeps plugin [id] (`name@marketplace`), named whole or by its name. */
    private fun keeps(id: String): Boolean = profile.keepPlugins.any { it == id || it == id.substringBefore(PLUGIN_AT) }

    private fun plugins(enabled: JsonObject, loaded: Set<String>): JsonObject = JsonObject(
        enabled.mapValues { (id, on) -> JsonPrimitive((on as? JsonPrimitive)?.booleanOrNull == true && id in loaded) },
    )

    /** The hooks table with the profile's drops taken out, and what is left empty removed. An entry that names a hook
     *  event drops that event's hooks and nothing else; any other entry drops every hook whose command holds it. */
    private fun hooks(table: JsonObject): JsonObject? {
        if (profile.keepHooks == KEEP_HOOKS_NONE) return null
        val kept = table.filterKeys { it !in eventDrops }.mapNotNull { (event, groups) ->
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
            wordDrops.any { command.contains(it) }
        }
        return if (left.isEmpty()) null else JsonObject(group + (Keys.HOOKS to JsonArray(left)))
    }
}

/** Claude Code's settings keys a profile filters. */
internal const val ENABLED_PLUGINS_KEY: String = "enabledPlugins"
private const val HOOK_COMMAND = "command"

// The `env` variables that lay a model list, which no profile carries.
private val modelVariables = ClaudeModelLists.variables.toSet()

// The hook events Claude Code runs (read in the 2.1.331 client), so a drop_hooks entry naming one drops that event
// even where no settings file's hooks table holds it, and never matches another event's commands.
private val HOOK_EVENTS = setOf(
    "PreToolUse", "PostToolUse", "PostToolUseFailure", "PostToolBatch", "Notification", "UserPromptSubmit",
    "UserPromptExpansion", "SessionStart", "SessionEnd", "Stop", "StopFailure", "SubagentStart", "SubagentStop",
    "PreCompact", "PostCompact", "PreModelSwitch", "PostModelSwitch", "PermissionRequest", "PermissionDenied", "Setup",
    "TeammateIdle", "TaskCreated", "TaskCompleted", "Elicitation", "ElicitationResult", "ConfigChange",
    "WorktreeCreate", "WorktreeRemove", "InstructionsLoaded", "CwdChanged", "FileChanged", "DirectoryAdded",
    "MessageDisplay",
)

// A plugin's id is its name, `@`, and the marketplace it came from.
internal const val PLUGIN_AT: Char = '@'
