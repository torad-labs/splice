// NEW: compose `splice install` / `uninstall` / `init` with the terminal (LAYOUT-01). The install
// verbs moved to features/launch and write through TerminalOutput; `init` writes the starter
// topology, which is app's wiring, so it stays here.
package splice.app

import splice.app.cli.AdminSupport
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.topology.TopologyLoader
import java.nio.file.Files

internal object InstallWiring {
    /** The install verbs against the operator's terminal: stdout for progress, stderr for refusals. */
    fun command(): InstallCommand = InstallCommand(TerminalOutput(::println), TerminalOutput(System.err::println))

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
