// NEW: MCP hub steps 3 and 4 — the daemon's answers to the two questions the hub asks about a launch, and the McpHub
// built over them. A launch id is the pid of the Claude Code process splice launched, as its launch record
// (LaunchOwners) names it: the record's birth check is what keeps a reused pid from passing for an ended session. Its
// folder is the process's working directory, its config home the CLAUDE_CONFIG_DIR it runs under, and the servers it
// loads are step 1's plan for that home and folder (McpLaunchPlanner), so a per-session copy is the one Claude Code
// itself would have started. An id splice cannot place loads the user-scope servers only.
//
// ON macOS (Oct 13), which has no /proc, the folder comes from lsof's cwd line and the environment from ps
// (DarwinPs, splice-sessions'), each in the shape the Linux read gives; the Linux reads are unchanged. UNTESTED ON A MAC:
// written against lsof(8) and ps(1) on a Linux machine, and the hub itself does not run there yet (McpHubGate).
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
import splice.launch.install.InstallLayout
import splice.sessions.registry.DarwinPs
import splice.sessions.registry.ProcessEnvironment
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

// why: the byte that ends each NAME=value entry in /proc/<pid>/environ.
private const val NUL_SEPARATOR = '\u0000'

internal class McpHubWiring(
    private val global: GlobalMcpServers,
    private val stateDir: Path,
    private val procRoot: Path = Paths.get("/proc"),
) {
    private val owners = LaunchOwners(stateDir)
    private val environment = ProcessEnvironment(procRoot, owners)
    private val planner = McpLaunchPlanner(
        UserHome.dir(),
        temple = splice.client.mcp.TempleMcpEntry(
            UserHome.dir(),
            paths = splice.core.config.StatePaths(baseOverride = stateDir),
        ),
    )
    private val darwin = DarwinPs()

    /** Why [hub] left the hub off, in the words its routes' 503 gives, or null once it is on; /health reports it. */
    var offReason: String? = null
        private set

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
     *  line either way. The front reads <state>/mcp-hub/access-key, the bearer [access] checks every request against.
     *  McpAccessKey owns that publication, including rotations, and adopts it for the current management key. */
    fun hub(
        sharing: McpSharing,
        startBound: McpHandshakeBudget,
        io: CoroutineDispatcher,
        log: LogSink,
        access: McpBearer,
    ): McpHubCalls {
        val bin = InstallLayout().shareDir(EnvReader(System::getenv))
        val refusal = McpHubGate(bin).refusal() ?: readAccess(access)
        offReason = refusal
        if (refusal != null) {
            log("[mcp-hub] ${LogSafe.str(refusal)}; MCP servers stay hosted in the daemon\n")
            return McpHubOff(refusal)
        }
        log("[mcp-hub] on: holders run from ${LogSafe.str(bin.toString())}\n")
        val paths = McpHubPaths(stateDir, bin.resolve("splice-mcp-holder"), UserHome.dir())
        return McpHub(sharing, servers, places, paths, startBound, io, log)
    }

    /** Adopts the canonical publication for the current management key, or says why it cannot be read. Adoption
     *  never restores a revoked v1 bearer. */
    private fun readAccess(access: McpBearer): String? = try {
        access()
        null
    } catch (e: IOException) {
        "the access key could not be read (${SafeFailureText.render(e)})"
    }

    private fun folderOf(pid: Long): Path? {
        if (darwin.here) return DarwinCwd().of(pid)
        return try {
            Files.readSymbolicLink(procRoot.resolve(pid.toString()).resolve("cwd"))
        } catch (_: IOException) {
            null
        }
    }

    private fun environ(pid: Long): Map<String, String> {
        if (darwin.here) return darwinEnviron(pid)
        return try {
            String(Files.readAllBytes(procRoot.resolve(pid.toString()).resolve("environ")))
                .split(NUL_SEPARATOR)
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        } catch (_: IOException) {
            emptyMap()
        }
    }

    /** The block ps gives, split as the Linux read splits /proc/<pid>/environ; empty when ps could not read it. */
    private fun darwinEnviron(pid: Long): Map<String, String> = darwin.environ(pid)?.decodeToString().orEmpty()
        .split(NUL_SEPARATOR)
        .filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}

/** A process's working folder on macOS: `lsof -a -p <pid> -d cwd -Fn`, which any user may run on their own processes,
 *  prints it on the line starting n. Null when lsof cannot say. */
private class DarwinCwd {
    fun of(pid: Long): Path? = try {
        val process = ProcessBuilder("/usr/sbin/lsof", "-a", "-p", pid.toString(), "-d", "cwd", "-Fn")
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val exited = process.waitFor(LSOF_WAIT_MS, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        out.takeIf { exited }?.lineSequence()?.firstOrNull { it.startsWith("n/") }?.let { Paths.get(it.substring(1)) }
    } catch (_: IOException) {
        null
    }
}

// why: how long lsof may take to name one process's folder before the launch reads as unplaced; it answers in milliseconds.
private const val LSOF_WAIT_MS = 2_000L
