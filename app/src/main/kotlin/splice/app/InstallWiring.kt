// NEW: compose `splice install` / `uninstall` / `init` with the terminal (LAYOUT-01). The install
// verbs moved to features/launch and write through TerminalOutput; `init` writes the starter
// topology, which is app's wiring, so it stays here.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.manager.ManagerHome
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.launch.install.InstallCommand
import splice.lifecycle.hub.HubUnits
import splice.lifecycle.units.DAEMON_UNIT_NAME
import splice.lifecycle.units.DaemonUnit
import splice.lifecycle.units.ListenerSockets
import splice.lifecycle.units.SpliceUnits
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.nio.file.Files
import java.nio.file.Path

internal object InstallWiring {
    /** The install verbs against the operator's terminal: stdout for progress, stderr for refusals. */
    fun command(): InstallCommand = InstallCommand(TerminalOutput(::println), TerminalOutput(System.err::println))

    /** After `splice install --all` on Linux: splice's own systemd user units, laid where systemd looks for a user's
     *  installed units (SpliceUnits). The daemon's runs the `splice` link this install made on the JDK it ran on, and
     *  the MCP hub's are laid when its front is installed beside the jar. */
    fun layUnits(target: String?, env: EnvReader) {
        if (target != null && target != "--all") return
        if (!linux()) return
        val output = TerminalOutput(::println)
        val folder = unitFolder(env, output)
        val hub = if (frontInstalled(env)) HubUnits(folder, output).templates() else emptyMap()
        val daemon = DaemonUnit(
            Path.of(System.getProperty("java.home")),
            InstallPaths(envReader = env).binDir.resolve("splice"),
        )
        val unit = daemon.bytes()
        if (unit == null) daemon.refusal()?.let(output::line)
        folder.lay(if (unit == null) hub else hub + (DAEMON_UNIT_NAME to unit))
    }

    /** The daemon's listening sockets as systemd units, which `splice restart` lays and starts so systemd holds
     *  splice's ports across a restart; null off Linux, where there is no systemd to hold them. */
    fun listenerSockets(env: EnvReader): ListenerSockets? {
        if (!linux()) return null
        val output = TerminalOutput(::println)
        return ListenerSockets(unitFolder(env, output), output)
    }

    /** The MCP hub's units, on Linux with the hub's front installed beside the jar; null anywhere else. */
    fun units(env: EnvReader): HubUnits? {
        if (!linux() || !frontInstalled(env)) return null
        val output = TerminalOutput(::println)
        return HubUnits(unitFolder(env, output), output)
    }

    /** Splice's units folder under the user's data home, beneath the user's own folder under the config home, whose
     *  units override splice's. The record of the copies splice once wrote into the latter lives in splice's state,
     *  under mcp-hub. */
    private fun unitFolder(env: EnvReader, output: TerminalOutput): SpliceUnits {
        val home = UserHome.dir(env)
        val data = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: home.resolve(".local").resolve("share")
        val config = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: home.resolve(".config")
        val record = StatePaths(envReader = env).stateDir.resolve("mcp-hub").resolve("units-written")
        val user = Path.of("systemd", "user")
        return SpliceUnits(data.resolve(user), config.resolve(user), record, output)
    }

    private fun linux(): Boolean = System.getProperty("os.name").startsWith("Linux")

    private fun frontInstalled(env: EnvReader): Boolean =
        Files.exists(InstallPaths(envReader = env).shareDir.resolve("splice-mcp-front"))

    /** `splice init`: the starter topology the install verbs read, written only when absent; the splice manager's folder
     *  beside it (ManagerHome), which install.sh installs by running init on every install; and on Linux the MCP hub
     *  front's environment file, which its unit reads (the hub runs under systemd --user, so nowhere else). */
    fun init(env: EnvReader) {
        val path = TopologyLoader.configPath(env)
        val existed = Files.exists(path)
        val topology = TopologyLoader.loadOrMaterialize(path)
        println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
        // The daemon's own state resolver, so init and boot keep one record of what splice wrote there.
        val stateDir = TopologyStatePaths(env).of(topology).stateDir
        ManagerHome(path, stateDir, LogSink { print(it) }).ensureBestEffort()
            ?.let { println("splice: the splice manager's folder is $it") }
        if (linux()) {
            val hubDir = StatePaths(envReader = env).stateDir.resolve("mcp-hub")
            McpFrontEnv(path.resolveSibling("mcp-front.env"))
                .write(hubDir, AdminSupport.controlPort(topology, env))
                ?.let(::println)
        }
    }
}
