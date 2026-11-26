// NEW: desktop-antigravity. `splice desktop wrapper [--print | --check]`: the settings Claude Desktop's Code tab needs
// from outside splice. Desktop starts a Code tab session through a process wrapper only when Claude Code's managed
// settings name one (`processWrapper`); splice's Desktop configuration already turns the wrapper on
// (`claudeCodeProcessWrapperEnabled`, written by `splice desktop on`), but the managed file is the administrator's,
// root-owned, and splice never writes it. It also needs `parentSettingsBehavior` "merge", so Desktop's own limits still
// apply to the Code sessions it starts and Desktop does not warn in each. Both verbs say the same things, from
// WrapperSettings, which the doctor and the upgrade item say too: what is wanted, what splice read, what it did not,
// what Desktop does (plainly only where it read every place), and what to do. `--print` (the default) also shows the
// whole file, and the commands that put it in place only where nothing is at its path. `--check` exits 0 where the
// launcher is laid and the file splice read names it, since the Code tab then starts through splice, and 1 otherwise.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.capability.WrapperSettings
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedFile
import splice.client.ManagedSettings
import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE
import splice.client.PROCESS_WRAPPER_KEY
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** The wrapper settings for the splice [env] names, said on [out], with what goes wrong on [err]: Claude Code's
 *  [managed] settings on [osName] (a test's own folder where one is given). */
internal class DesktopWrapper(
    private val env: EnvReader,
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    osName: String = System.getProperty(OS_NAME).orEmpty(),
    private val managed: ManagedSettings = ManagedSettings(osName),
) {
    private val windows = osName.lowercase().startsWith("windows")
    private val launch: Path get() = DesktopPrograms(StatePaths(envReader = env).stateDir).launch

    fun run(args: List<String>): Int = when (args) {
        emptyList<String>(), listOf(WRAPPER_PRINT_FLAG) -> print(settings())
        listOf(WRAPPER_CHECK_FLAG) -> check(settings())
        else -> {
            err.line("usage: splice desktop wrapper [$WRAPPER_PRINT_FLAG | $WRAPPER_CHECK_FLAG]")
            LAUNCH_USAGE_EXIT
        }
    }

    private fun settings() = WrapperSettings(managed.reading(UserHome.claudeDir(env)), launch)

    private fun print(settings: WrapperSettings): Int {
        out.line("Claude Code's managed settings need both of these keys:")
        out.line(PRETTY.encodeToString(JsonObject.serializer(), settings.wanted()))
        out.line(WHY)
        out.line("")
        listOfNotNull(settings.found(), settings.unread(), settings.effect())
            .forEach { out.line("${it.replaceFirstChar(Char::uppercase)}.") }
        val step = settings.step()
        if (step != null) {
            out.line("What to do: $step.")
            if (!settings.right) commands(settings)
            out.line("Then $REOPEN")
            out.line("`splice desktop wrapper --check` says whether it took.")
        }
        laid()
        return 0
    }

    /** How to put the file in place where nothing is at its path, or how to edit it where something may be. */
    private fun commands(settings: WrapperSettings) {
        val file = settings.file
        when {
            settings.state != ManagedFile.Absent && windows -> out.line("  Edit it in an editor run as administrator.")
            settings.state != ManagedFile.Absent -> out.line("  For example: sudoedit '$file'")
            windows -> {
                out.line("  Save the text above as managed-settings.json and, in a terminal run as administrator:")
                out.line("  New-Item -ItemType Directory -Force '${file.parent}'")
                out.line("  Copy-Item managed-settings.json '$file'")
            }
            else -> {
                out.line("  Save the text above as managed-settings.json and run:")
                out.line("  sudo mkdir -p '${file.parent}' && sudo install -m 0644 managed-settings.json '$file'")
            }
        }
    }

    /** The same things as [print], each line marked; 0 where the launcher is laid and the file splice read names it. */
    private fun check(settings: WrapperSettings): Int {
        val starts = settings.starts && Files.isExecutable(launch)
        listOfNotNull(settings.found(), settings.unread(), settings.effect()).forEach { out.line("splice: $it.") }
        settings.pointed()?.let { out.line("splice: what to do: $it, then $REOPEN") }
        laid()
        return if (starts) 0 else 1
    }

    private fun laid() {
        if (!Files.isExecutable(launch)) out.line("splice: $launch is not laid yet: run `splice desktop on` first.")
    }
}

private val PRETTY = Json { prettyPrint = true }

// Why each key is there, said once beside them.
private const val WHY = "$PROCESS_WRAPPER_KEY starts Claude Desktop's Code tab through splice. $PARENT_SETTINGS_KEY " +
    "\"$PARENT_SETTINGS_MERGE\" keeps the limits in Desktop's own policy applied to the Code sessions it starts."

// Desktop reads its configuration at launch, so a change reaches it only after the whole app restarts.
private const val REOPEN = "quit Claude Desktop entirely and reopen it."

private const val WRAPPER_PRINT_FLAG = "--print"
private const val WRAPPER_CHECK_FLAG = "--check"
