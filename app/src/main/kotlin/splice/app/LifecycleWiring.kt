// NEW: compose the daemon's CLI lifecycle — `splice restart`, and the cold start `splice dashboard`
// shares with it — with the terminal, the environment and the running jar (LAYOUT-01). The verbs
// moved to features/lifecycle; where this build's jar lives is app's to answer (AdminSupport.selfJar).
package splice.app

import splice.app.cli.AdminSupport
import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.restart.RestartCommand
import splice.lifecycle.start.DaemonColdStart

internal object LifecycleWiring {
    private val output = TerminalOutput(::println)
    private val errors = TerminalOutput(System.err::println)
    private val jar = RunningJar(AdminSupport::selfJar)

    /** [expectedVersion] is what the restarted daemon must report: this CLI's own, or the release an
     *  upgrade just activated. */
    fun restart(expectedVersion: String = GATEWAY_VERSION): Boolean =
        RestartCommand(output, errors, EnvReader(System::getenv), jar).restart(expectedVersion)

    fun ensureDaemon(port: Int): Boolean =
        DaemonColdStart(output, errors, EnvReader(System::getenv), jar).ensureDaemon(port)
}
