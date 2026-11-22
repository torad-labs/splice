// NEW: head-catalog (gw-check). Claude Code's ways of laying a model list in `/model` beside or in place of what its
// gateway discovery finds, as facts of its dialect: the settings keys (an allowlist and its switch, picker rows, a tier
// mapping), the variables a settings file's `env` can set (each tier's model, name and description, the small model,
// a custom option), the cache it keeps in a Claude folder's .claude.json, and where an organisation's managed settings
// live. `splice claude check` names each it finds; nothing here writes.
package splice.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

public object ClaudeModelLists {
    /** The settings keys that lay a model list. */
    public val settingsKeys: List<String> =
        listOf("availableModels", "enforceAvailableModels", "modelPicker", "modelOverrides")

    /** The variables that lay a model list from a settings file's `env`. */
    public val variables: List<String> = ClaudeTiers.names.flatMap(ClaudeTiers::variables) +
        listOf("ANTHROPIC_SMALL_FAST_MODEL", "ANTHROPIC_CUSTOM_MODEL_OPTION")

    /** The picker rows Claude Code caches in a Claude folder's .claude.json, as an earlier launch of a head wrote. */
    public const val STATE_CACHE: String = "additionalModelOptionsCache"

    /** The settings files of a Claude folder that can carry a list: its settings and its local settings. */
    public val settingsFiles: List<String> = listOf(Keys.SETTINGS_FILE_NAME, "settings.local.json")

    /** Where an organisation's managed settings live on this machine, by Claude Code's own paths. */
    public fun managed(osName: String): Path? = when {
        osName.startsWith("Linux") -> Paths.get("/etc/claude-code/managed-settings.json")
        osName.startsWith("Mac") -> Paths.get("/Library/Application Support/ClaudeCode/managed-settings.json")
        else -> null
    }

    /** The lists a settings object lays: each key by name, and each variable of its `env` as `env.<name>`. */
    public fun inSettings(settings: JsonObject): List<String> {
        val env = (settings["env"] as? JsonObject)?.keys.orEmpty()
        return settingsKeys.filter { it in settings } + variables.filter { it in env }.map { "env.$it" }
    }

    /** The lists a .claude.json holds. */
    public fun inState(state: JsonObject): List<String> = listOf(STATE_CACHE).filter { it in state }

    /** [file] read as a JSON object, or null when it is absent, unreadable or not an object, as Claude Code itself
     *  reads a settings file it cannot parse: as setting nothing. */
    public fun read(file: Path): JsonObject? = try {
        Json.parseToJsonElement(Files.readString(file)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }
}
