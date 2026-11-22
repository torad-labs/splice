// NEW: desktop-policy (docs/specs/desktop-full.md, outcome 9). What a person already set up in Claude Code that Claude
// Desktop's configuration can carry, read from their Claude folder and never written: the effort their sessions run at
// (`effortLevel` in `settings.json`) and the plugin marketplaces they added (`plugins/known_marketplaces.json`). A file
// that is not there, can't be read or is not the shape Claude Code writes says nothing, so Desktop keeps splice's
// default.
//
// Left where Claude Code keeps it, each for a reason Desktop gives: MCP servers (one managed server makes Desktop start
// its Code sessions with MCP from plugins and policy only, which drops the person's own servers there), permission
// rules (Code sessions read them already, and as policy they would also bind Cowork and Chat), the status line (Desktop
// runs one only from a root-owned file outside the home folder) and the Claude login (gateway mode has none).
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.nio.file.Path

internal class ClaudeCodeSetup(private val claudeDir: Path) {
    /** The effort the person's sessions run at, when it is a level Desktop's own selector has. */
    fun effort(): String? = settingString(CLAUDE_EFFORT_LEVEL)?.takeIf { it in DESKTOP_EFFORT_LEVELS }

    /** The default permission mode in Claude Code (`permissions.defaultMode`), mapped to Desktop's
     *  `codeDefaultPermissionMode` vocabulary when recognized. */
    fun permissionMode(): String? {
        val raw = settingPermissionDefaultMode()?.trim() ?: return null
        return when (raw) {
            "default" -> "manual"
            in DESKTOP_PERMISSION_MODES -> raw
            else -> null
        }
    }

    /** Whether prompt suggestions are explicitly configured in Claude Code `settings.local.json` or `settings.json`. */
    fun promptSuggestionEnabled(): Boolean? =
        settingString(CLAUDE_PROMPT_SUGGESTION)?.toBooleanStrictOrNull()

    /** The marketplaces the person added that Desktop's configuration can name, combining
     *  `plugins/known_marketplaces.json`, `settings.json`, and `settings.local.json`'s `extraKnownMarketplaces`.
     *  A GitHub repository, git remote or hosted marketplace file is carried with its branch and folder;
     *  local directories and sparse-path repositories are omitted because Desktop cannot express them faithfully. */
    fun marketplaces(): List<JsonObject> {
        val fromKnown = held(KNOWN_MARKETPLACES_FILE)?.values.orEmpty()
            .mapNotNull { known -> named((known as? JsonObject)?.get(MARKETPLACE_SOURCE)) }
        val fromSettings = extraMarketplaces(DESKTOP_SETTINGS_FILE)
        val fromLocal = extraMarketplaces(CLAUDE_LOCAL_SETTINGS_FILE)
        return fromKnown + fromSettings + fromLocal
    }

    private fun extraMarketplaces(file: String): List<JsonObject> {
        val extraObj = held(file)?.get(EXTRA_KNOWN_MARKETPLACES) as? JsonObject
        return extraObj?.values.orEmpty()
            .mapNotNull { entry -> named((entry as? JsonObject)?.get(MARKETPLACE_SOURCE)) }
    }

    private fun settingString(key: String): String? =
        JsonScalars.str(held(CLAUDE_LOCAL_SETTINGS_FILE), key) ?: JsonScalars.str(held(DESKTOP_SETTINGS_FILE), key)

    private fun settingPermissionDefaultMode(): String? {
        val localPerms = held(CLAUDE_LOCAL_SETTINGS_FILE)?.get(CLAUDE_PERMISSIONS) as? JsonObject
        val basePerms = held(DESKTOP_SETTINGS_FILE)?.get(CLAUDE_PERMISSIONS) as? JsonObject
        return JsonScalars.str(localPerms, CLAUDE_DEFAULT_MODE) ?: JsonScalars.str(basePerms, CLAUDE_DEFAULT_MODE)
    }

    /** [source], a marketplace's source as Claude Code holds it, as Desktop's configuration names it; null for one
     *  Desktop can't be given. */
    private fun named(source: JsonElement?): JsonObject? {
        val held = (source as? JsonObject)?.takeUnless { it[MARKETPLACE_SPARSE_PATHS] is JsonArray }
        val kind = JsonScalars.str(held, MARKETPLACE_SOURCE)
        val address = marketplaceAddressOf.entries.firstOrNull { it.key == kind }
        val at = address?.let { JsonScalars.str(held, it.value) }?.takeIf(String::isNotBlank)
        if (address == null || at == null) return null
        return buildJsonObject {
            put(MARKETPLACE_SOURCE, address.key)
            put(address.value, at)
            JsonScalars.str(held, MARKETPLACE_REF)?.let { put(MARKETPLACE_REF, it) }
            JsonScalars.str(held, MARKETPLACE_FOLDER)?.let { put(MARKETPLACE_FOLDER, it) }
        }
    }

    private fun held(file: String): JsonObject? =
        (DesktopSettingsRead(claudeDir.resolve(file)).read() as? SettingsRead.Held)?.file
}

// Claude Code's own key for the effort its sessions run at, in `settings.json` / `settings.local.json`.
private const val CLAUDE_LOCAL_SETTINGS_FILE = "settings.local.json"
private const val CLAUDE_EFFORT_LEVEL = "effortLevel"
private const val CLAUDE_PERMISSIONS = "permissions"
private const val CLAUDE_DEFAULT_MODE = "defaultMode"
private const val CLAUDE_PROMPT_SUGGESTION = "promptSuggestionEnabled"
private const val EXTRA_KNOWN_MARKETPLACES = "extraKnownMarketplaces"

// The levels Desktop's `defaultModelEffort` takes; Claude Code has others (`auto`) that Desktop would read as none.
private val DESKTOP_EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

// The values Desktop's `codeDefaultPermissionMode` accepts.
private val DESKTOP_PERMISSION_MODES = setOf("manual", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions")

// Where Claude Code keeps every marketplace it knows, under its config folder: one entry per name, each with `source`.
private const val KNOWN_MARKETPLACES_FILE = "plugins/known_marketplaces.json"

/** A marketplace's kind, in Claude Code's file and in Desktop's configuration alike. */
internal const val MARKETPLACE_SOURCE: String = "source"

/** The key each kind of marketplace Desktop can reach has its address under, in both files. */
internal val marketplaceAddressOf: Map<String, String> = mapOf("github" to "repo", "git" to "url", "url" to "url")

// The branch, tag or commit, and the folder inside the repository, where Claude Code keeps either.
private const val MARKETPLACE_REF = "ref"
private const val MARKETPLACE_FOLDER = "path"

// Set on a marketplace Claude Code clones only these folders of.
private const val MARKETPLACE_SPARSE_PATHS = "sparsePaths"
