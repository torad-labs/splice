// NEW: Claude Desktop's side of what CapabilityLook reads: the program, the mode splice has it in (its record, in the
// state folder splice.toml names, against Desktop's library), the side-by-side launchers, and what Claude Code's
// managed settings say for its Code tab (WrapperSettings). Split from CapabilityLook.kt (Nov 23, 2026,
// concentration). It only reads.
package splice.app.capability

import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopAppMode
import splice.app.desktop.setup.DesktopMode
import splice.app.desktop.setup.DesktopPrograms
import splice.app.desktop.setup.SideBySideDesktop
import splice.app.desktop.setup.SideBySideLaunchers
import splice.client.ManagedSettings
import splice.client.desktop.DesktopAppConfig
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.launch.install.InstallCommand
import splice.topology.TopologyStatePaths
import java.nio.file.Files

/** [managed] is Claude Code's managed settings on this OS; null reads none. */
internal class DesktopFactsRead(
    private val env: EnvReader,
    private val osName: String,
    private val managed: ManagedSettings? = ManagedSettings(osName),
) {
    private val quiet = TerminalOutput { }

    fun read(topology: Topology?): DesktopFacts {
        val program = Cancellables.runCatchingBestEffort {
            SideBySideDesktop(env, quiet, quiet, osName = osName).findClaudeDesktop()
        }.getOrNull()
        val cli = Cancellables.runCatchingBestEffort {
            InstallCommand(quiet, quiet).runsShim(CLAUDE_SPLICE_COMMAND, env)
        }.getOrDefault(false)
        val app = Files.exists(SideBySideLaunchers(env, osName).desktopLauncherPath())
        val settings = Cancellables.runCatchingBestEffort {
            val launch = DesktopPrograms(TopologyStatePaths(env).of(topology).stateDir).launch
            managed?.reading(env)?.let { WrapperSettings(it, launch) }
        }.getOrNull()
        return DesktopFacts(program, mode(topology), cli, app, settings)
    }

    /** Desktop's mode as `splice doctor` reads it (DesktopLook): splice's record, in [topology]'s state folder, against
     *  Desktop's library. */
    private fun mode(topology: Topology?): DesktopMode = Cancellables.runCatchingBestEffort {
        val paths = TopologyStatePaths(env).of(topology)
        DesktopAppMode(
            DesktopRecords(paths.desktopFile, LogSink { }),
            DesktopAppLibrary(DesktopAppConfig(env, osName)),
            DesktopPrograms(paths.stateDir),
        ).mode()
    }.getOrDefault(DesktopMode.OFF)
}
