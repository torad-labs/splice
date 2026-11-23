// NEW: what this machine has of the optional things splice works beside, read once and said three ways: `splice doctor`
// rows (CapabilityTools), the "action needed" list `splice upgrade` and `splice setup --update` print (CapabilityGaps),
// and what `splice setup` reports it found. One reader, so the three never disagree about what is installed.
// It only reads: the PATH, Claude Desktop's program and splice's record of its mode, the side-by-side launchers,
// Claude Code's managed settings (for a processWrapper), the Antigravity shim, the MCP hub's programs and each
// harness's MCP file. Nothing here writes, starts a process or opens a socket.
package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopAppMode
import splice.app.desktop.setup.DesktopMode
import splice.app.desktop.setup.DesktopPrograms
import splice.app.desktop.setup.SideBySideDesktop
import splice.app.desktop.setup.SideBySideLaunchers
import splice.app.mcp.McpHarnessDiscovery
import splice.client.ClaudeModelLists
import splice.client.desktop.DesktopAppConfig
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.launch.install.GeminiShim
import splice.launch.install.InstallCommand
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.nio.file.Files
import java.nio.file.Path

/** Whether Claude Code's managed settings name a `processWrapper`, and whose: splice's `desktop-launch`, another
 *  program, or none (no managed file, or one without the key). */
internal enum class WrapperState { SPLICE, OTHER, NONE }

/** Claude Desktop: its [program] (null when not installed), the [mode] splice has it in, whether `claude-splice`
 *  ([sideBySideCli]) and the `Claude (Splice)` app launcher ([sideBySideApp]) are installed, and the managed
 *  [wrapper]. */
internal data class DesktopFacts(
    val program: Path?,
    val mode: DesktopMode,
    val sideBySideCli: Boolean,
    val sideBySideApp: Boolean,
    val wrapper: WrapperState,
)

/** The other harnesses: Antigravity's [agy] and [codex] on the PATH (null when absent), whether the Antigravity
 *  [geminiShim] is laid, and the harnesses whose MCP file already points at splice ([synced]). */
internal data class HarnessFacts(
    val agy: Path?,
    val codex: Path?,
    val geminiShim: Boolean,
    val synced: Set<McpHarness>,
)

/** The MCP hub: whether this is [linux], the hub's front is installed with sharing not turned off ([on]), and
 *  `systemctl` and `systemd-run` are both on the PATH ([systemd]), which the hub runs under. */
internal data class HubFacts(val linux: Boolean, val on: Boolean, val systemd: Boolean)

/** Everything [CapabilityLook] read, and `[daemon] auto_update` as splice.toml says it (null: the file never said). */
internal data class Capabilities(
    val desktop: DesktopFacts,
    val harness: HarnessFacts,
    val hub: HubFacts,
    val autoUpdate: Boolean?,
)

internal class CapabilityLook(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val quiet = TerminalOutput { }

    fun read(): Capabilities {
        val topology = topology()
        return Capabilities(desktop(), harness(), hub(topology), topology?.daemon?.autoUpdate)
    }

    private fun desktop(): DesktopFacts {
        val program = Cancellables.runCatchingBestEffort {
            SideBySideDesktop(env, quiet, quiet, osName = osName).findClaudeDesktop()
        }.getOrNull()
        val cli = Cancellables.runCatchingBestEffort {
            InstallCommand(quiet, quiet).runsShim(CLAUDE_SPLICE_COMMAND, env)
        }.getOrDefault(false)
        val app = Files.exists(SideBySideLaunchers(env, osName).desktopLauncherPath())
        return DesktopFacts(program, mode(), cli, app, wrapper())
    }

    /** Desktop's mode as `splice doctor` reads it (DesktopLook): splice's record against Desktop's library. */
    private fun mode(): DesktopMode = Cancellables.runCatchingBestEffort {
        val paths = TopologyStatePaths(env).current()
        DesktopAppMode(
            DesktopRecords(paths.desktopFile, LogSink { }),
            DesktopAppLibrary(DesktopAppConfig(env, osName)),
            DesktopPrograms(paths.stateDir),
        ).mode()
    }.getOrDefault(DesktopMode.OFF)

    /** The `processWrapper` Claude Code's managed settings name, by Claude Code's own path for this OS. */
    private fun wrapper(): WrapperState {
        val named = Cancellables.runCatchingBestEffort {
            val file = ClaudeModelLists.managed(osName)?.takeIf { Files.isRegularFile(it) }
            file?.let { JsonScalars.objectOrNull(Json, Files.readString(it)) }?.get(PROCESS_WRAPPER)
        }.getOrNull() ?: return WrapperState.NONE
        // A program path, or a command line as a list whose first word is the program.
        val program = (named as? JsonArray)?.firstOrNull()?.let(JsonScalars::str) ?: JsonScalars.str(named)
        return if (program?.endsWith(DESKTOP_LAUNCH) == true) WrapperState.SPLICE else WrapperState.OTHER
    }

    private fun harness(): HarnessFacts {
        val discovery = Cancellables.runCatchingBestEffort { McpHarnessDiscovery(UserHome.dir(env), env, osName) }
            .getOrNull()
        val synced = buildSet {
            if (discovery != null && pointsAtSplice(discovery.agyConfigFile())) add(McpHarness.AGY)
            if (discovery != null && pointsAtSplice(discovery.codexConfigFile())) add(McpHarness.CODEX)
        }
        return HarnessFacts(onPath(AGY), onPath(CODEX), GeminiShim(env).current(), synced)
    }

    private fun hub(topology: Topology?): HubFacts {
        val linux = osName.startsWith(LINUX)
        val front = Files.isRegularFile(InstallPaths(envReader = env).shareDir.resolve(HUB_FRONT))
        val on = linux && front && topology?.daemon?.mcpShare != false
        return HubFacts(linux, on, onPath(SYSTEMCTL) != null && onPath(SYSTEMD_RUN) != null)
    }

    /** Whether [file] holds an MCP server on splice's loopback `/mcp/` route, as harness sync writes it. */
    private fun pointsAtSplice(file: Path): Boolean = Cancellables.runCatchingBestEffort {
        Files.isRegularFile(file) && SPLICE_MCP_URL.containsMatchIn(Files.readString(file))
    }.getOrDefault(false)

    private fun topology(): Topology? = Cancellables.runCatchingBestEffort {
        val path = TopologyLoader.configPath(env)
        if (Files.isRegularFile(path)) TopologyLoader.parse(Files.readString(path)) else null
    }.getOrNull()

    /** [name]'s first executable on [env]'s PATH, or null. */
    private fun onPath(name: String): Path? = env(PATH).orEmpty().split(':').asSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { dir -> Cancellables.runCatchingBestEffort { Path.of(dir, name) }.getOrNull() }
        .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
}

private const val PATH = "PATH"
private const val AGY = "agy"
private const val CODEX = "codex"
private const val SYSTEMCTL = "systemctl"
private const val SYSTEMD_RUN = "systemd-run"
private const val LINUX = "Linux"
private const val HUB_FRONT = "splice-mcp-front"
private const val PROCESS_WRAPPER = "processWrapper"

// The program DesktopPrograms lays for a managed processWrapper to name.
private const val DESKTOP_LAUNCH = "desktop-launch"

// What harness sync writes for a Splice MCP server: the hub front's or the control plane's loopback `/mcp/` route.
private val SPLICE_MCP_URL = Regex("""http://127\.0\.0\.1:\d+/mcp/""")
