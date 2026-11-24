// NEW: server mode (docs/specs/server-mode.md, F8), and #791 (managed settings detection). The settings an organisation
// manages for Claude Code, which no other settings override, at Claude Code's own paths, written once here: the
// platform's `managed-settings.json` and, beside it, the `managed-settings.d` folder whose `*.json` drop-ins Claude Code
// merges over it in filename order, skipping hidden files; and the `remote-settings.json` Claude Code keeps in a config
// tree for settings the organisation sends it. `splice claude check`, `splice desktop wrapper`, the MCP plan and a
// launch's telemetry decision all read those paths from here. Where any of the files sets telemetry of its own, a launch
// adds none of splice's: the organisation's collector stays the one the session reports to (#791 widened that read from
// `remote-settings.json` alone). A file that is absent or can't be read sets nothing, as Claude Code itself reads it.
package splice.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
     *  OpenTelemetry variable in their `env`, or a headers helper of their own, in any one of their files. */
    public fun setsTelemetry(configDir: Path): Boolean =
        // why: a Path is an Iterable of its own names, so the one file goes in a list of its own, never added bare.
        (sources() + listOf(configDir.resolve(REMOTE_SETTINGS))).any { file ->
            read(file)?.let(::telemetry) == true
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

    private fun telemetry(managed: JsonObject): Boolean {
        val env = (managed[MANAGED_ENV] as? JsonObject).orEmpty().keys
        return Keys.OTEL_HEADERS_HELPER in managed || env.any { it == TELEMETRY_SWITCH || it.startsWith(OTEL_PREFIX) }
    }

    private fun read(file: Path): JsonObject? = try {
        Json.parseToJsonElement(Files.readString(file)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
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
