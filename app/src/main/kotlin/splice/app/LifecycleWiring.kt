// NEW: compose the daemon's CLI lifecycle — `splice restart`, and the cold start `splice dashboard`
// shares with it — with the terminal, the environment and the running jar (LAYOUT-01). The verbs
// moved to features/lifecycle; where this build's jar lives is app's to answer (AdminSupport.selfJar).
// V4-220 item 4: and the console's upgrade, run in its own user scope.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.cli.ConfigFixOffer
import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.launch.install.InstallLayout
import splice.lifecycle.restart.ConfigRepair
import splice.lifecycle.restart.RestartCommand
import splice.lifecycle.start.DaemonColdStart
import splice.lifecycle.upgrade.SystemdUpgradeLauncher
import splice.lifecycle.upgrade.UpgradeRuns

internal object LifecycleWiring {
    private val output = TerminalOutput(::println)
    private val errors = TerminalOutput(System.err::println)
    private val jar = RunningJar(AdminSupport::selfJar)

    /** The jar an upgrade restarts onto: the install's live jar, which the activation just repointed. The jar this
     *  CLI runs from is the OLD release, so a daemon no unit supervises came back on it (found on a desk, 2026-10-12). */
    fun installedJar(): RunningJar = RunningJar {
        InstallLayout().shareDir(EnvReader(System::getenv)).resolve("splice.jar")
    }

    /** [expectedVersion] is what the restarted daemon must report: this CLI's own, or the release an
     *  upgrade just activated. [waitForCompactions] false skips V4-216's wait (`--now`, upgrade).
     *  [launchJar] is the jar a cold start runs: this build's own unless an upgrade names the live one. */
    fun restart(
        expectedVersion: String = GATEWAY_VERSION,
        waitForCompactions: Boolean = true,
        launchJar: RunningJar = jar,
    ): Boolean =
        RestartCommand(
            output,
            errors,
            EnvReader(System::getenv),
            launchJar,
            repair = ConfigRepair { ConfigFixOffer().offer() },
        ).restart(expectedVersion, waitForCompactions)

    fun ensureDaemon(port: Int): Boolean =
        DaemonColdStart(output, errors, EnvReader(System::getenv), jar).ensureDaemon(port)

    /** `splice start` through the supervisor unit ([DaemonColdStart.startThroughUnit]); null runs the daemon here. */
    fun startThroughUnit(): Int? =
        DaemonColdStart(output, errors, EnvReader(System::getenv), jar).startThroughUnit(AdminSupport.controlPort())

    /** `splice upgrade` for the console, in a scope that inherits the daemon's own environment. */
    fun consoleUpgrades(): UpgradeRuns {
        val env = EnvReader(System::getenv)
        return UpgradeRuns(env, SystemdUpgradeLauncher(env))
    }
}
