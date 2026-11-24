// NEW: what this machine has of the optional things splice works beside, read once and said three ways: `splice doctor`
// rows (CapabilityTools), the "action needed" list `splice upgrade` and `splice setup --update` print (CapabilityGaps),
// and what `splice setup` reports it found. One reader, so the three never disagree about what is installed.
// It only reads: the PATH, Claude Desktop's program and splice's record of its mode, the side-by-side launchers,
// Claude Code's managed settings (for a processWrapper), the Antigravity shim, the MCP hub's programs and each
// harness's MCP file. Nothing here writes, starts a process or opens a socket.
package splice.app.capability

import splice.app.mcp.McpHarnessDiscovery
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** [desktop] reads Claude Desktop's side (DesktopFactsRead). */
internal class CapabilityLook(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val desktop: DesktopFactsRead = DesktopFactsRead(env, osName),
) {
    /** What [topology] (splice.toml as parsed; by default read from disk, null where it is missing or broken) and
     *  this machine say. Its `[daemon] state_dir` decides where Desktop's record is read, as the daemon reads it. */
    fun read(topology: Topology? = topology()): Capabilities =
        Capabilities(desktop.read(topology), harness(), hub(topology), topology?.daemon?.autoUpdate)

    private fun harness(): HarnessFacts {
        val home = UserHome.dir(env)
        val discovery = Cancellables.runCatchingBestEffort { McpHarnessDiscovery(home, env, osName) }
            .getOrNull()
        val synced = buildSet {
            if (discovery != null && pointsAtSplice(discovery.agyConfigFile())) add(McpHarness.AGY)
            if (discovery != null && pointsAtSplice(discovery.codexConfigFile())) add(McpHarness.CODEX)
        }
        val codex = onPath(CODEX_BIN)
        val codexHooks = codex?.let {
            Cancellables.runCatchingBestEffort {
                val hooks = CodexPeerHooks()
                hooks.inspect(hooks.hooksFile(home, env))
            }.getOrNull()
        }
        return HarnessFacts(onPath(AGY_BIN), codex, GeminiShim(env).current(), synced, codexHooks)
    }

    private fun hub(topology: Topology?): HubFacts {
        val linux = osName.startsWith(LINUX_OS)
        val front = Files.isRegularFile(InstallPaths(envReader = env).shareDir.resolve(HUB_FRONT_BIN))
        val on = linux && front && topology?.daemon?.mcpShare != false
        return HubFacts(linux, on, onPath(SYSTEMCTL_BIN) != null && onPath(SYSTEMD_RUN_BIN) != null)
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
    private fun onPath(name: String): Path? = env(PATH_ENV).orEmpty().split(':').asSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { dir -> Cancellables.runCatchingBestEffort { Path.of(dir, name) }.getOrNull() }
        .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
}

private const val PATH_ENV = "PATH"
private const val AGY_BIN = "agy"
private const val CODEX_BIN = "codex"
private const val SYSTEMCTL_BIN = "systemctl"
private const val SYSTEMD_RUN_BIN = "systemd-run"
private const val LINUX_OS = "Linux"
private const val HUB_FRONT_BIN = "splice-mcp-front"

// What harness sync writes for a Splice MCP server: the hub front's or the control plane's loopback `/mcp/` route.
private val SPLICE_MCP_URL = Regex("""http://127\.0\.0\.1:\d+/mcp/""")
