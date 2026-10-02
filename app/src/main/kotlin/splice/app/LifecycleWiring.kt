// NEW: compose the daemon's CLI lifecycle — `splice restart`, and the cold start `splice dashboard`
// shares with it — with the terminal, the environment and the running jar (LAYOUT-01). The verbs
// moved to features/lifecycle; where this build's jar lives is app's to answer (AdminSupport.selfJar).
// V4-220 item 4: and the console's upgrade, run in its own user scope.
package splice.app

import splice.app.cli.AdminSupport
import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.restart.RestartCommand
import splice.lifecycle.start.DaemonColdStart
import splice.lifecycle.upgrade.SystemdUpgradeLauncher
import splice.lifecycle.upgrade.UpgradeRuns

internal object LifecycleWiring {
    private val output = TerminalOutput(::println)
    private val errors = TerminalOutput(System.err::println)
    private val jar = RunningJar(AdminSupport::selfJar)

    /** [expectedVersion] is what the restarted daemon must report: this CLI's own, or the release an
     *  upgrade just activated. [waitForCompactions] false skips V4-216's wait (`--now`, upgrade). */
    fun restart(expectedVersion: String = GATEWAY_VERSION, waitForCompactions: Boolean = true): Boolean =
        RestartCommand(output, errors, EnvReader(System::getenv), jar).restart(expectedVersion, waitForCompactions)

    fun ensureDaemon(port: Int): Boolean =
        DaemonColdStart(output, errors, EnvReader(System::getenv), jar).ensureDaemon(port)

    /** `splice start`: the exit code once the supervisor unit has started the daemon, as every cold start
     *  does where one serves this shell (V4-190); null where none does, and the daemon runs in this
     *  process as `start` always has. Oct 1, 11:49 PM CT: a `start` typed while splice.service
     *  restarted took its port, with no heap cap and nothing to restart it. */
    fun startThroughUnit(): Int? {
        val coldStart = DaemonColdStart(output, errors, EnvReader(System::getenv), jar)
        coldStart.supervisorUnit() ?: return null
        return if (coldStart.ensureDaemon(AdminSupport.controlPort())) 0 else 1
    }

    /** `splice upgrade` for the console, in a scope that inherits the daemon's own environment. */
    fun consoleUpgrades(): UpgradeRuns {
        val env = EnvReader(System::getenv)
        return UpgradeRuns(env, SystemdUpgradeLauncher(env))
    }
}
