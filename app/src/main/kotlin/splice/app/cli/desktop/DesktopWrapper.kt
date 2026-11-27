// NEW: desktop-antigravity. `splice desktop wrapper [--print | --check]`: the settings Claude Desktop's Code tab needs
// from outside splice. Desktop starts a Code tab session through a process wrapper only when Claude Code's managed
// settings name one (`processWrapper`); splice's Desktop configuration already turns the wrapper on
// (`claudeCodeProcessWrapperEnabled`, written by `splice desktop on`), but the managed files are the administrator's,
// root-owned, and splice never writes them. It also needs `parentSettingsBehavior` "merge" in the source that wins.
// Both verbs say the same things, from WrapperSettings, which the doctor and the upgrade item say too: what is wanted,
// what splice read and which file holds each key, the places it could not read, what Desktop does (only as the
// condition), and the file to edit. `--print` (the default) also shows the wanted keys as a file, and the commands
// that put it in place only where nothing is at its path and no drop-in was read. `--check` exits 0 where the
// launcher is laid and the files splice read, merged, name it, and 1 otherwise: it confirms no more than that.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.capability.AFTER_CHANGE
import splice.app.capability.WrapperSettings
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.topology.TopologyStatePaths
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
    private val launch: Path get() = DesktopPrograms(TopologyStatePaths(env).current().stateDir).launch

    fun run(args: List<String>): Int = when (args) {
        emptyList<String>(), listOf(WRAPPER_PRINT_FLAG) -> print(settings())
        listOf(WRAPPER_CHECK_FLAG) -> check(settings())
        else -> {
            err.line("usage: splice desktop wrapper [$WRAPPER_PRINT_FLAG | $WRAPPER_CHECK_FLAG]")
            LAUNCH_USAGE_EXIT
        }
    }

    private fun settings() = WrapperSettings(managed.reading(env), launch)

    private fun print(settings: WrapperSettings): Int {
        out.line("Claude Code's managed settings need both of these keys:")
        out.line(PRETTY.encodeToString(JsonObject.serializer(), settings.wanted()))
        out.line("")
        settings.facts().forEach { out.line("${it.replaceFirstChar(Char::uppercase)}.") }
        settings.step()?.let { step ->
            out.line("What to do: $step.")
            commands(settings)
            out.line("${AFTER_CHANGE.replaceFirstChar(Char::uppercase)}.")
        }
        return 0
    }

    /** How to put the main file in place where it is to be created whole, or how to edit each file to change. */
    private fun commands(settings: WrapperSettings) {
        val file = settings.file
        when {
            !settings.creates && windows -> out.line("  Edit each file named above in an editor run as administrator.")
            !settings.creates -> settings.edits().keys.forEach { out.line("  For example: sudoedit '$it'") }
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

    /** The same things as [print], each line marked; 0 where the launcher is laid and the merged files name it. */
    private fun check(settings: WrapperSettings): Int {
        (listOf(settings.wantedLine()) + settings.facts()).forEach { out.line("splice: $it.") }
        settings.pointed()?.let { out.line("splice: what to do: $it.") }
        return if (settings.starts && settings.laid) 0 else 1
    }
}

private val PRETTY = Json { prettyPrint = true }

private const val WRAPPER_PRINT_FLAG = "--print"
private const val WRAPPER_CHECK_FLAG = "--check"
