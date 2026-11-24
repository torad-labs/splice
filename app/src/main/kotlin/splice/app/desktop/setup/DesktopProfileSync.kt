// NEW: desktop-policy. Seamless first-run migration of safe, non-auth user setup from a user's
// existing 1P Claude Desktop (`~/.config/Claude/` on Linux or `~/Library/Application Support/Claude/`
// on macOS) into a 3P gateway profile (`<profile>/Claude-3p/`). Fills what is missing and never overwrites existing 3P
// settings, never follows symlinks, and never reads cookies, credentials, or keyring stores.
//
// desktop-3p-parity (Nov 23, 2026): the profile is given what Claude Code and the 1P Desktop have, so Chat and Cowork
// on the gateway work as they do there. Its MCP servers are Splice's, as every harness is given them
// (DesktopMcpSource): each stdio server Desktop can start itself, and each one Splice hosts or the person reaches at a
// URL through the stdio bridge, an entry splice keeps current at every sync and takes out when the server is gone
// (DesktopMcpEntries). Extensions are copied with their programs runnable (DesktopExtensionsSync), and the person's
// own skills are given to its skills plugins (DesktopSkillsSync).
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopProfileSync(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    /** The person's home, where Claude Code's folder and the 1P Desktop's are. */
    private val home: Path = UserHome.dir(env),
    private val files: DesktopLibraryFiles = DesktopLibraryFiles(),
    private val mcp: DesktopMcpSource = SpliceDesktopMcp(env, home),
) {
    private val extensionsSync = DesktopExtensionsSync(files, DesktopTreeCopy())
    private val skillsSync = DesktopSkillsSync(files, DesktopTreeCopy(), home.resolve(UserHome.CLAUDE_DIR))

    /** Migrates safe non-auth settings, Splice's MCP servers, missing 1P Desktop extensions and the person's skills
     *  into [targetAppData] (e.g. `~/.config/claude-splice/Claude-3p`). Returns true if any file under
     *  [targetAppData] was created or updated. */
    fun syncInto(targetAppData: Path): Boolean {
        val configChanged = syncConfigInto(targetAppData)
        val extensionsChanged = extensionsSync.sync(sourceAppDataPath(), targetAppData)
        val skillsChanged = skillsSync.sync(sourceAppDataPath(), targetAppData)
        return configChanged || extensionsChanged || skillsChanged
    }

    /** [targetAppData]'s `claude_desktop_config.json` given Splice's MCP servers and the 1P Desktop's settings it
     *  lacks; whether it changed. */
    fun syncConfigInto(targetAppData: Path): Boolean {
        val source = sourceConfigPath()
        val target = targetAppData.resolve(DESKTOP_CONFIG_FILE)
        val sameFile = source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()
        val upstreamDesktop = if (!sameFile && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            files.read(source)
        } else {
            null
        }
        val wanted = mcp.wanted()
        val current = files.updatable(target)
        val bridge = wanted.program?.mcp?.toString()
        val merged = mergeConfig(current, upstreamDesktop, wanted, bridge)
        // Laid at every sync that bridges anything, so the bridge follows an upgrade of splice even when no entry
        // moves.
        if (wanted.bridged.isNotEmpty()) wanted.program?.layMcp()
        if (merged == current) return false
        Files.createDirectories(targetAppData)
        files.write(target, merged)
        return true
    }

    internal fun sourceAppDataPath(): Path {
        val base = if (osName.startsWith("Mac", ignoreCase = true)) {
            home.resolve("Library/Application Support")
        } else {
            env(SYNC_XDG_CONFIG_HOME)?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve(".config")
        }
        return base.resolve(DEFAULT_CLAUDE_DIR)
    }

    internal fun sourceConfigPath(): Path = sourceAppDataPath().resolve(DESKTOP_CONFIG_FILE)

    /** [current] with [wanted]'s servers (bridged ones through [bridge], when there is one) and [upstreamDesktop]'s
     *  servers, preferences and settings it lacks. */
    internal fun mergeConfig(
        current: JsonObject,
        upstreamDesktop: JsonObject?,
        wanted: DesktopMcpWanted = DesktopMcpWanted(),
        bridge: String? = null,
    ): JsonObject {
        val additions = mutableMapOf<String, JsonElement>()
        val upstreamServers = (upstreamDesktop?.get(KEY_MCP_SERVERS) as? JsonObject).orEmpty()
        val missing = JsonObject(wanted.stdio + upstreamServers)
        // A server the person set up in the 1P Desktop keeps that setup, as it does over Claude Code's stdio entry.
        val bridged = wanted.bridged.filter { it !in upstreamServers }
        servers(current, missing, bridged, bridge)?.let { additions[KEY_MCP_SERVERS] = it }
        JsonMapMerger.mergeMissing(current, upstreamDesktop?.get(KEY_PREFERENCES) as? JsonObject, KEY_PREFERENCES)
            ?.let { additions[KEY_PREFERENCES] = it }
        SCALAR_KEYS.filter { it !in current }.forEach { scalarKey ->
            upstreamDesktop?.get(scalarKey)?.let { additions[scalarKey] = it }
        }
        return if (additions.isEmpty()) current else JsonObject(current + additions)
    }

    /** [current]'s servers as they should be, or null when they need no change or are not a map. */
    private fun servers(current: JsonObject, missing: JsonObject, bridged: List<String>, bridge: String?): JsonObject? {
        val before = when (val held = current[KEY_MCP_SERVERS]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> held
            is JsonArray, is JsonPrimitive -> null
        }
        if (bridge == null || before == null) return JsonMapMerger.mergeMissing(current, missing, KEY_MCP_SERVERS)
        return DesktopMcpEntries(bridge).merged(before, bridged, missing).takeIf { it != before }
    }
}

private const val SYNC_XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
private const val DEFAULT_CLAUDE_DIR = "Claude"
private const val DESKTOP_CONFIG_FILE = "claude_desktop_config.json"
private const val KEY_MCP_SERVERS = "mcpServers"
private const val KEY_PREFERENCES = "preferences"
private val SCALAR_KEYS = listOf("coworkUserFilesPath", "isHardwareAccelerationDisabled")
