// NEW: server mode (docs/specs/server-mode.md, F8). The settings an organisation manages for a Claude Code config tree,
// which Claude Code keeps in `remote-settings.json` there and lets no other settings override. Where they set telemetry
// of their own, a launch adds none of splice's: the organisation's collector stays the one the session reports to.
// A file that is absent or can't be read sets nothing, as Claude Code itself reads it.
package splice.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

public class ManagedSettings {
    /** Whether the managed settings of the tree at [configDir] set telemetry: the switch, any OpenTelemetry variable
     *  in their `env`, or a headers helper of their own. */
    public fun setsTelemetry(configDir: Path): Boolean {
        val managed = read(configDir.resolve(REMOTE_SETTINGS)) ?: return false
        val env = (managed[SETTINGS_ENV] as? JsonObject).orEmpty().keys
        return Keys.OTEL_HEADERS_HELPER in managed ||
            env.any { it == TELEMETRY_SWITCH || it.startsWith(OTEL_PREFIX) }
    }

    private fun read(file: Path): JsonObject? = try {
        Json.parseToJsonElement(Files.readString(file)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }
}

/** The file Claude Code keeps an organisation's managed settings in, inside a config tree. */
private const val REMOTE_SETTINGS = "remote-settings.json"
private const val SETTINGS_ENV = "env"

/** The variable that turns Claude Code's telemetry on. */
public const val TELEMETRY_SWITCH: String = "CLAUDE_CODE_ENABLE_TELEMETRY"

// Every OpenTelemetry variable, the exporters and the endpoint among them, starts so.
private const val OTEL_PREFIX = "OTEL_"
