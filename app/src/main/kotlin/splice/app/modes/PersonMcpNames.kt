// NEW: modes-mcp name clash (Nov 25, 2026). The names of the person's own MCP servers, for the mcp modes to yield a
// name the person already uses, from every place they can declare one that splice reads or writes: what the daemon's
// MCP host reads (McpGlobalRead: what they imported into splice, `~/.claude.json`'s and the Temple's), the usual
// Desktop's `claude_desktop_config.json` (whose servers splice copies into a gateway profile), and each gateway
// profile's own (`Claude-3p`, McpDesktopSync), where every entry but splice's own bridge entry is theirs. The modes ask
// at every request to their address, so the servers are read again only when one of those files has changed since the
// last reading: a stat a file, and a listing of Desktop's config folder for the profiles beside it.
package splice.app.modes

import kotlinx.serialization.json.JsonObject
import splice.app.McpGlobalRead
import splice.app.auth.claude.CLAUDE_JSON
import splice.app.desktop.setup.DesktopMcpEntries
import splice.app.desktop.setup.DesktopProfileSync
import splice.app.desktop.setup.DesktopPrograms
import splice.app.mcp.McpDesktopSync
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The person's own MCP server names for [home], [paths] and the environment [env] names Desktop's folders by, read
 *  again only when their files change. */
internal class PersonMcpNames(
    home: Path,
    paths: StatePaths,
    env: EnvReader = paths.envReader,
    osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val temple = TempleMcpEntry(home, paths = paths)
    private val registry = McpUserRegistry(paths)
    private val read = McpGlobalRead(home, LogSink {}, temple, registry)
    private val files = listOf(home.resolve(CLAUDE_JSON), registry.file, temple.registrationFile)
    private val desktop = DesktopProfileSync(env, osName, home)
    private val usualDesktop = desktop.sourceAppDataPath()
    private val gateways = McpDesktopSync(home, paths, env, osName)
    private val bridge = DesktopMcpEntries(DesktopPrograms(paths.stateDir).mcp.toString())

    @Volatile
    private var last: Reading? = null

    fun names(): Set<String> {
        val profiles = gatewayProfiles()
        val stamps = (files + (listOf(usualDesktop) + profiles).map(desktop::configIn)).map(::stamp)
        last?.takeIf { it.stamps == stamps }?.let { return it.names }
        val declared = read.inputs().map { it.name } + servers(usualDesktop).keys
        val inProfiles = profiles.flatMap { profile -> servers(profile).filterValues { !bridge.ours(it) }.keys }
        val names = (declared + inProfiles).toSet()
        last = Reading(stamps, names)
        return names
    }

    /** Each gateway profile; none when Desktop's config folder can't be listed. */
    private fun gatewayProfiles(): List<Path> = try {
        gateways.profiles()
    } catch (_: IOException) {
        emptyList()
    }

    /** The servers of the Desktop profile [appData]; none when it holds none or can't be read. */
    private fun servers(appData: Path): JsonObject = try {
        desktop.serversIn(appData)
    } catch (_: IOException) {
        JsonObject(emptyMap())
    }

    /** [path]'s modification time and size, or null when it is no file or can't be asked. */
    private fun stamp(path: Path): Pair<FileTime, Long>? = try {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.getLastModifiedTime(path) to Files.size(path)
        } else {
            null
        }
    } catch (_: IOException) {
        null
    }

    private data class Reading(val stamps: List<Pair<FileTime, Long>?>, val names: Set<String>)
}
