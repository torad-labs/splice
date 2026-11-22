// NEW: desktop-policy. Seamless first-run migration of safe, non-auth user setup from a user's
// existing 1P Claude Desktop (`~/.config/Claude/claude_desktop_config.json` on Linux or
// `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS) into a 3P gateway
// profile (`<profile>/Claude-3p/claude_desktop_config.json`). Only fills missing entries; never
// overwrites existing 3P settings and never reads cookies, credentials, or keyring stores.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopProfileSync(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val files: DesktopLibraryFiles = DesktopLibraryFiles(),
) {
    /** Merges safe non-auth settings from the user's default 1P Claude Desktop config into [targetAppData]
     *  (e.g. `~/.config/claude-splice/Claude-3p`). Returns true if [targetAppData]/claude_desktop_config.json
     *  was created or updated. */
    fun syncInto(targetAppData: Path): Boolean {
        val source = sourceConfigPath()
        val target = targetAppData.resolve(DESKTOP_CONFIG_FILE)
        val sameFile = source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()
        val upstream = if (!sameFile && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            files.read(source)
        } else {
            null
        } ?: return false
        val current = files.read(target) ?: JsonObject(emptyMap())
        val merged = mergeConfig(current, upstream)
        if (merged == current) return false
        Files.createDirectories(targetAppData)
        files.write(target, merged)
        return true
    }

    internal fun sourceConfigPath(): Path {
        val base = if (osName.startsWith("Mac", ignoreCase = true)) {
            UserHome.dir(env).resolve("Library/Application Support")
        } else {
            env(SYNC_XDG_CONFIG_HOME)?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: UserHome.dir(env).resolve(".config")
        }
        return base.resolve(DEFAULT_CLAUDE_DIR).resolve(DESKTOP_CONFIG_FILE)
    }

    internal fun mergeConfig(current: JsonObject, upstream: JsonObject): JsonObject {
        val additions = mutableMapOf<String, JsonElement>()
        mergeObjectKey(current, upstream, KEY_MCP_SERVERS)?.let { additions[KEY_MCP_SERVERS] = it }
        mergeObjectKey(current, upstream, KEY_PREFERENCES)?.let { additions[KEY_PREFERENCES] = it }
        for (scalarKey in SCALAR_KEYS) {
            if (scalarKey !in current) upstream[scalarKey]?.let { additions[scalarKey] = it }
        }
        return if (additions.isEmpty()) current else JsonObject(current + additions)
    }

    private fun mergeObjectKey(current: JsonObject, upstream: JsonObject, key: String): JsonObject? {
        val src = (upstream[key] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: return null
        val held = when {
            key !in current -> JsonObject(emptyMap())
            current[key] is JsonObject -> current[key] as JsonObject
            else -> return null
        }
        val missing = src.filterKeys { it !in held }
        return if (missing.isEmpty()) null else JsonObject(held + missing)
    }
}

private const val SYNC_XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
private const val DEFAULT_CLAUDE_DIR = "Claude"
private const val DESKTOP_CONFIG_FILE = "claude_desktop_config.json"
private const val KEY_MCP_SERVERS = "mcpServers"
private const val KEY_PREFERENCES = "preferences"
private val SCALAR_KEYS = listOf("coworkUserFilesPath", "isHardwareAccelerationDisabled")
