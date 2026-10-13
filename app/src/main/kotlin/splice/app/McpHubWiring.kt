// NEW: MCP hub steps 3 and 4 — the daemon's answers to the two questions the hub asks about a launch, and the McpHub
// built over them. A launch id is the pid of the Claude Code process splice launched, as its launch record
// (LaunchOwners) names it: the record's birth check is what keeps a reused pid from passing for an ended session. Its
// folder is the process's working directory, its config home the CLAUDE_CONFIG_DIR it runs under, and the servers it
// loads are step 1's plan for that home and folder (McpLaunchPlanner), so a per-session copy is the one Claude Code
// itself would have started. An id splice cannot place loads the user-scope servers only.
package splice.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpBearer
import splice.client.mcp.McpLaunchPlanner
import splice.client.mcp.McpSharing
import splice.control.mcp.GlobalMcpServers
import splice.control.mcp.McpHandshakeBudget
import splice.control.mcp.hub.McpHub
import splice.control.mcp.hub.McpHubCalls
import splice.control.mcp.hub.McpHubGate
import splice.control.mcp.hub.McpHubOff
import splice.control.mcp.hub.McpHubPaths
import splice.control.mcp.hub.McpLaunchEnv
import splice.control.mcp.hub.McpLaunchPlace
import splice.control.mcp.hub.McpLaunchPlaces
import splice.control.mcp.hub.McpLaunchServers
import splice.core.config.UserHome
import splice.core.process.LaunchOwners
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.launch.install.InstallLayout
import splice.sessions.registry.ProcessEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// why: the byte that ends each NAME=value entry in /proc/<pid>/environ.
private const val NUL = '\u0000'

internal class McpHubWiring(
    private val global: GlobalMcpServers,
    private val stateDir: Path,
    private val procRoot: Path = Paths.get("/proc"),
) {
    private val owners = LaunchOwners(stateDir)
    private val environment = ProcessEnvironment(procRoot, owners)
    private val planner = McpLaunchPlanner(UserHome.dir())

    /** A launch's place: its working folder, and its environment read only when a per-session copy starts. */
    val places: McpLaunchPlaces = McpLaunchPlaces { launch ->
        val pid = launch.toLongOrNull()?.takeIf { owners.read(it) != null }
        val folder = pid?.let(::folderOf)
        if (pid == null || folder == null) null else McpLaunchPlace(folder, McpLaunchEnv { environ(pid) })
    }

    /** The servers a launch loads: step 1's plan for its home and folder, or the user-scope servers. */
    val servers: McpLaunchServers = McpLaunchServers { launch, place ->
        val pid = launch.toLongOrNull()
        val home = place?.let { pid?.let { environment.configHome(it, UserHome.claudeDir()) } }
        if (place == null || home == null) {
            global()
        } else {
            JsonObject(planner.plan(home, place.folder).servers.associate { it.name to it.entry })
        }
    }

    /** The hub, or its off answer when this machine cannot run it (McpHubGate, decided once here at boot), with one log
     *  line either way. A running hub writes <state>/mcp-hub/access-key, the bearer [access] the front checks every
     *  session against; the management key is fixed for the process, so one write at boot keeps it current. */
    fun hub(
        sharing: McpSharing,
        startBound: McpHandshakeBudget,
        io: CoroutineDispatcher,
        log: LogSink,
        access: McpBearer,
    ): McpHubCalls {
        val bin = InstallLayout().shareDir(EnvReader(System::getenv))
        val refusal = McpHubGate(bin).refusal() ?: writeAccess(access)
        if (refusal != null) {
            log("[mcp-hub] ${LogSafe.str(refusal)}; MCP servers stay hosted in the daemon\n")
            return McpHubOff(refusal)
        }
        log("[mcp-hub] on: holders run from ${LogSafe.str(bin.toString())}\n")
        val paths = McpHubPaths(stateDir, bin.resolve("splice-mcp-holder"), UserHome.dir())
        return McpHub(sharing, servers, places, paths, startBound, io, log)
    }

    /** Writes the bearer the front checks sessions against, or says why it could not, which keeps the hub off. */
    private fun writeAccess(access: McpBearer): String? = try {
        val dir = stateDir.resolve("mcp-hub")
        Files.createDirectories(dir)
        SecureFile.writeAtomic0600(dir.resolve("access-key"), access())
        null
    } catch (e: IOException) {
        "the access key could not be written (${SafeFailureText.render(e)})"
    }

    private fun folderOf(pid: Long): Path? = try {
        Files.readSymbolicLink(procRoot.resolve(pid.toString()).resolve("cwd"))
    } catch (_: IOException) {
        null
    }

    private fun environ(pid: Long): Map<String, String> = try {
        String(Files.readAllBytes(procRoot.resolve(pid.toString()).resolve("environ")))
            .split(NUL)
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    } catch (_: IOException) {
        emptyMap()
    }
}
