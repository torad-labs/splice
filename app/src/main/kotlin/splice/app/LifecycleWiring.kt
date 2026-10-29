// NEW: compose the daemon's CLI lifecycle — `splice restart`, and the cold start `splice dashboard`
// shares with it — with the terminal, the environment and the running jar (LAYOUT-01). The verbs
// moved to features/lifecycle; where this build's jar lives is app's to answer (AdminSupport.selfJar).
// V4-220 item 4: and the console's upgrade, run in its own user scope.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.cli.ConfigFixOffer
import splice.app.provider.LocalRowBootFindings
import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.install.InstallLayout
import splice.lifecycle.restart.ConfigRepair
import splice.lifecycle.restart.RestartCommand
import splice.lifecycle.start.DaemonColdStart
import splice.lifecycle.upgrade.AutoUpdatePolicy
import splice.lifecycle.upgrade.AutoUpdateSettings
import splice.lifecycle.upgrade.SystemdUpgradeLauncher
import splice.lifecycle.upgrade.UpgradeAuto
import splice.lifecycle.upgrade.UpgradeRuns
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

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
            runtimes = LocalRowBootFindings(EnvReader(System::getenv)),
        ).let { command ->
            val sockets = InstallWiring.listenerSockets(EnvReader(System::getenv))
            if (sockets == null) {
                command.restart(expectedVersion, waitForCompactions)
            } else {
                command.restart(expectedVersion, waitForCompactions, sockets)
            }
        }

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

    /** The daemon's updater over [runs], its policy read from the booted splice.toml at [path] at every look, so an edit
     *  applies to the next one. A file that will not parse skips that look; the boot findings already refuse a bad one. */
    fun autoUpdate(runs: UpgradeRuns, path: Path, log: LogSink): UpgradeAuto =
        UpgradeAuto(EnvReader(System::getenv), runs, updateSettings(path, log), log)

    /** `[daemon] auto_update` and `update_channel` from the splice.toml at [path], read fresh at every ask. */
    fun updateSettings(path: Path, log: LogSink): AutoUpdateSettings = AutoUpdateSettings { policy(path, log) }

    /** The same for a CLI verb: the splice.toml the daemon reads, with nothing logged. */
    fun cliUpdateSettings(): AutoUpdateSettings = updateSettings(TopologyLoader.configPath(), LogSink {})

    private fun policy(path: Path, log: LogSink): AutoUpdatePolicy? {
        val daemon = Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(path)).daemon }
            .onFailure {
                log("[auto-update] splice.toml unread this look: ${LogSafe.str(SafeFailureText.render(it))}\n")
            }
            .getOrNull() ?: return null
        return AutoUpdatePolicy(enabled = daemon.autoUpdate == true, beta = daemon.updateChannel == "beta")
    }
}
