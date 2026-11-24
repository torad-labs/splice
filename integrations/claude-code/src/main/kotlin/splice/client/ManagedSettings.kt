// NEW: server mode (docs/specs/server-mode.md, F8), and #791 (managed settings detection). The settings an organisation
// manages for Claude Code, which no other settings override, at Claude Code's own paths, written once here: the
// platform's `managed-settings.json` and, beside it, the `managed-settings.d` folder whose `*.json` drop-ins Claude Code
// merges over it in filename order, skipping hidden files; and the `remote-settings.json` Claude Code keeps in a config
// tree for settings the organisation sends it. `splice claude check`, `splice desktop wrapper`, the MCP plan and a
// launch's telemetry decision all read those paths from here. Claude Code uses one managed source and never merges two:
// `remote-settings.json` when it sets anything, the platform's files otherwise, merged as Claude Code merges them (a
// later drop-in's value replacing or, with `null`, clearing an earlier one's). Where that source sets telemetry of its
// own (a `null` or blank value sets nothing), a launch adds none of splice's: the organisation's collector stays the
// one the session reports to (#791 widened that read from `remote-settings.json` alone). A file that is absent, can't
// be read, or is no JSON object as `JSON.parse` reads one (an unquoted word or a raw control character in a string
// makes it none; a key written twice keeps its last value) sets nothing, as Claude Code itself reads it: a
// `remote-settings.json` so broken leaves the platform's files the source. A macOS configuration profile
// (`com.anthropic.claudecode`) or a Windows registry policy (`HKLM\SOFTWARE\Policies\ClaudeCode`) is not read here.
package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** The managed settings of a machine on [osName] (this one's when it is not given), whose platform folder is
 *  [platformDir] where one is given (a test's own folder) and Claude Code's own for that system otherwise:
 *  `/Library/Application Support/ClaudeCode` on macOS, `C:\Program Files\ClaudeCode` on Windows, and
 *  `/etc/claude-code` on Linux and every other system. */
public class ManagedSettings(
    osName: String = System.getProperty(OS_NAME_PROPERTY).orEmpty(),
    platformDir: Path? = null,
) {
    private val platform: Path = platformDir ?: Path.of(
        when {
            osName.lowercase().startsWith("mac") -> MAC_MANAGED_DIR
            osName.lowercase().startsWith("windows") -> WINDOWS_MANAGED_DIR
            else -> LINUX_MANAGED_DIR
        },
    )

    /** The platform's own managed settings file. */
    public val file: Path get() = platform.resolve(MANAGED_SETTINGS_FILE)

    /** Every managed settings file of the platform, in the order Claude Code merges them: [file], then each `*.json`
     *  of `managed-settings.d` in filename order. A folder that is absent or can't be listed adds no drop-in. */
    public fun sources(): List<Path> = listOf(file) + dropIns()

    /** Whether the managed settings that reach a session of the tree at [configDir] set telemetry: the switch, any
     *  OpenTelemetry variable in their `env`, or a headers helper of their own, in the one source that is [active]. */
    public fun setsTelemetry(configDir: Path): Boolean = telemetry(active(configDir))

    /** The one managed source that reaches a session of the tree at [configDir], as Claude Code picks it: the tree's
     *  `remote-settings.json` when it is an object with any key, and the platform's files as they merge
     *  ([effective]) otherwise. Claude Code never merges the two, so nothing of the platform's rides along under it. */
    private fun active(configDir: Path): JsonObject =
        read(configDir.resolve(REMOTE_SETTINGS))?.takeIf { it.isNotEmpty() } ?: effective()

    /** The platform's managed settings as Claude Code merges its files ([sources]), each over the ones before it: a
     *  later file's value for a key replaces an earlier one, a list joins the earlier one's, an object such as `env`
     *  merges key by key, and a key a later file sets to `null` is no longer set. A file that is absent, unreadable
     *  or no object adds nothing. */
    private fun effective(): JsonObject = sources().mapNotNull(::read).fold(EMPTY, ::merged)

    /** [over] laid on [under], key by key: a `null` in [over] at any depth, the first file's own included, sets
     *  nothing. */
    private fun merged(under: JsonObject, over: JsonObject): JsonObject {
        val keys = under.keys + over.keys
        val laid = keys.mapNotNull { key ->
            val value = if (key in over) over.getValue(key) else under.getValue(key)
            val below = under[key]
            when {
                value is JsonNull -> null
                value is JsonObject -> key to merged(below as? JsonObject ?: EMPTY, value)
                value is JsonArray && below is JsonArray -> key to JsonArray((below + value).distinct())
                else -> key to value
            }
        }
        return JsonObject(laid.toMap())
    }

    private fun dropIns(): List<Path> {
        val folder = platform.resolve(MANAGED_DROP_IN_DIR)
        if (!Files.isDirectory(folder)) return emptyList()
        return try {
            Files.list(folder).use { listed ->
                listed.toList().filter { it.name.endsWith(DROP_IN_SUFFIX) && !it.name.startsWith(".") }
                    .sortedBy { it.name }
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Whether [managed] sets telemetry; a key whose value is blank sets nothing, as an unset one does. */
    private fun telemetry(managed: JsonObject): Boolean {
        val env = (managed[MANAGED_ENV] as? JsonObject).orEmpty().filterValues(::sets).keys
        val helper = managed[Keys.OTEL_HEADERS_HELPER]?.let(::sets) == true
        return helper || env.any { it == TELEMETRY_SWITCH || it.startsWith(OTEL_PREFIX) }
    }

    private fun sets(value: JsonElement): Boolean =
        value !is JsonNull && (value !is JsonPrimitive || value.content.isNotBlank())

    private fun read(file: Path): JsonObject? = try {
        // why: Claude Code reads these files with `JSON.parse`, which keeps the last of a repeated key.
        JsonScalars.strictObjectOrNull(Json, Files.readString(file), lastKeyWins = true)
    } catch (_: IOException) {
        null
    }
}

private const val OS_NAME_PROPERTY = "os.name"
private const val LINUX_MANAGED_DIR = "/etc/claude-code"
private const val MAC_MANAGED_DIR = "/Library/Application Support/ClaudeCode"
private const val WINDOWS_MANAGED_DIR = "C:\\Program Files\\ClaudeCode"
private const val MANAGED_SETTINGS_FILE = "managed-settings.json"
private const val MANAGED_DROP_IN_DIR = "managed-settings.d"
private const val DROP_IN_SUFFIX = ".json"

/** The file Claude Code keeps an organisation's remotely managed settings in, inside a config tree. */
private const val REMOTE_SETTINGS = "remote-settings.json"
private const val MANAGED_ENV = "env"

/** The variable that turns Claude Code's telemetry on. */
public const val TELEMETRY_SWITCH: String = "CLAUDE_CODE_ENABLE_TELEMETRY"

// Every OpenTelemetry variable, the exporters and the endpoint among them, starts so.
private const val OTEL_PREFIX = "OTEL_"

private val EMPTY = JsonObject(emptyMap())
