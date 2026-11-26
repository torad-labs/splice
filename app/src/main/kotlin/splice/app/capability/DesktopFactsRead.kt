// NEW: Claude Desktop's side of what CapabilityLook reads: the program, the mode splice has it in (its record, in the
// state folder splice.toml names, against Desktop's library), the side-by-side launchers, and the processWrapper
// Claude Code's managed settings name. Split from CapabilityLook.kt (Nov 23, 2026, concentration). It only reads.
package splice.app.capability

import kotlinx.serialization.json.JsonArray
import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopAppMode
import splice.app.desktop.setup.DesktopMode
import splice.app.desktop.setup.DesktopPrograms
import splice.app.desktop.setup.SideBySideDesktop
import splice.app.desktop.setup.SideBySideLaunchers
import splice.client.ManagedSettings
import splice.client.PROCESS_WRAPPER_KEY
import splice.client.ParentSetting
import splice.client.PolicyDocument
import splice.client.desktop.DesktopAppConfig
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.launch.install.InstallCommand
import splice.topology.TopologyStatePaths
import java.nio.file.Files
import java.nio.file.Path

/** [managed] is Claude Code's managed settings on this OS, whose file names a processWrapper and a
 *  parentSettingsBehavior; null reads none. */
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
        val document = Cancellables.runCatchingBestEffort { managed?.fileDocument() }.getOrNull()
        return DesktopFacts(program, mode(topology), cli, app, wrapper(document), managedFile(document))
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

    /** The `processWrapper` the managed [document] names: a program path,
     *  or a command line as a list whose first word is the program. Splice's when that program is `desktop-launch`
     *  (DesktopPrograms); none when the key is absent or names no program, and unreadable where the file is there and
     *  could not be read. */
    private fun wrapper(document: PolicyDocument?): WrapperState {
        if (document?.unreadable == true) return WrapperState.UNREADABLE
        val named = document?.settings?.get(PROCESS_WRAPPER_KEY)
        val program = Cancellables.runCatchingBestEffort {
            (named as? JsonArray)?.firstOrNull()?.let(JsonScalars::str) ?: JsonScalars.str(named)
        }.getOrNull()
        if (program.isNullOrBlank()) return WrapperState.NONE
        val name = Cancellables.runCatchingBestEffort { Path.of(program.trim()).fileName?.toString() }.getOrNull()
        return if (name == DESKTOP_LAUNCH) WrapperState.SPLICE else WrapperState.OTHER
    }

    /** The managed settings as [document] read the file, with the other places Claude Code reads that hold something
     *  and that are not read here; null where there is no file and no other source. */
    private fun managedFile(document: PolicyDocument?): ManagedFileFacts? {
        val settings = managed ?: return null
        val elsewhere = Cancellables.runCatchingBestEffort { settings.otherSources() }.getOrDefault(emptyList())
        val state = when {
            document == null -> ManagedFileState.MISSING
            document.unreadable -> ManagedFileState.UNREADABLE
            else -> ManagedFileState.READ
        }
        if (state == ManagedFileState.MISSING && elsewhere.isEmpty()) return null
        return ManagedFileFacts(settings.file, state, document?.parentSetting() ?: ParentSetting(), elsewhere)
    }
}

// The program DesktopPrograms lays for a managed processWrapper to name.
private const val DESKTOP_LAUNCH = "desktop-launch"
