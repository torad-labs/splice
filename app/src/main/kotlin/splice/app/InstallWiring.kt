// NEW: compose `splice install` / `uninstall` / `init` with the terminal (LAYOUT-01). The install
// verbs moved to features/launch and write through TerminalOutput; `init` writes the starter
// topology, which is app's wiring, so it stays here.
package splice.app

import splice.app.cli.AdminSupport
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.lifecycle.hub.HubUnits
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal object InstallWiring {
    /** The install verbs against the operator's terminal: stdout for progress, stderr for refusals. */
    fun command(): InstallCommand = InstallCommand(TerminalOutput(::println), TerminalOutput(System.err::println))

    /** After `splice install --all` on Linux, with the MCP hub's front installed beside the jar: the hub's systemd units,
     *  created only where none of that name is installed (HubUnits). */
    fun hubUnits(target: String?, env: EnvReader) {
        if (target != null && target != "--all") return
        units(env)?.install()
    }

    /** The MCP hub's units in the user's own systemd/user folder, on Linux with the hub's front installed beside the
     *  jar; null anywhere else. Splice's record of the units it wrote lives in its own state, under mcp-hub. */
    fun units(env: EnvReader): HubUnits? {
        if (!System.getProperty("os.name").startsWith("Linux")) return null
        if (!Files.exists(InstallPaths(envReader = env).shareDir.resolve("splice-mcp-front"))) return null
        val config = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: UserHome.dir(env).resolve(".config")
        val record = StatePaths(envReader = env).stateDir.resolve("mcp-hub").resolve("units-written")
        return HubUnits(config.resolve("systemd").resolve("user"), record, TerminalOutput(::println))
    }

    /** `splice init`: the starter topology the install verbs read, written only when absent, and on Linux the MCP hub
     *  front's environment file, which its unit reads (the hub runs under systemd --user, so nowhere else). */
    fun init(env: EnvReader) {
        val path = TopologyLoader.configPath(env)
        val existed = Files.exists(path)
        val topology = TopologyLoader.loadOrMaterialize(path)
        println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
        if (System.getProperty("os.name").startsWith("Linux")) {
            val hubDir = StatePaths(envReader = env).stateDir.resolve("mcp-hub")
            McpFrontEnv(path.resolveSibling("mcp-front.env"))
                .write(hubDir, AdminSupport.controlPort(topology, env))
                ?.let(::println)
        }
    }
}
