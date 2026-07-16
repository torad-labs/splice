// NEW: splice CLI dispatch (P5-CLI grows this). In cli/ so the walls exempt its runBlocking use
// (admin one-shots, not daemon hot path). :app is exempt from no-println — a terminal tool writes
// to stdout. Subcommands: doctor, version. install/init/login land with P5.
package splice.app.cli

import kotlinx.coroutines.runBlocking
import splice.app.TopologyLoader
import splice.core.config.StatePaths
import java.nio.file.Files

@Suppress("CyclomaticComplexMethod") // a flat subcommand dispatch
public fun runCli(args: Array<String>) {
    when (args.firstOrNull()) {
        "doctor" -> doctor()
        "version" -> println("splice kt-1")
        "init" -> InstallCommand.init()
        "install" -> InstallCommand.install(args.getOrNull(1))
        "uninstall" -> InstallCommand.uninstall(args.getOrNull(1))
        "login" -> runBlocking { LoginCommand.login(args.getOrNull(1)) }
        "setup" -> runBlocking { SetupCommand.setup() }
        "status" -> StatusCommand.status()
        "dashboard" -> DashboardCommand.dashboard()
        else -> System.err.println(
            "usage: splice [setup|status|dashboard|login <head>|install|uninstall|init|doctor|daemon|version]",
        )
    }
}

private fun doctor() {
    val statePaths = StatePaths()
    val topologyPath = TopologyLoader.configPath()
    fun present(exists: Boolean) = if (exists) "present" else "absent"
    println("splice doctor")
    println("  java:        ${System.getProperty("java.version")}")
    println("  state dir:   ${statePaths.stateDir} (${present(Files.exists(statePaths.stateDir))})")
    println("  topology:    $topologyPath (${present(Files.exists(topologyPath))})")
    println("  mgmt-key:    ${present(Files.exists(statePaths.mgmtKeyFile))}")
    println("  daemon lock: ${statePaths.daemonLockFile}")
}
