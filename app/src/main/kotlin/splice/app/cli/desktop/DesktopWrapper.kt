// NEW: desktop-antigravity. `splice desktop wrapper [--print | --check]`: the settings Claude Desktop's Code tab needs
// from outside splice. Desktop starts a Code tab session through a process wrapper only when Claude Code's managed
// settings name one (`processWrapper`); splice's Desktop configuration already turns the wrapper on
// (`claudeCodeProcessWrapperEnabled`, written by `splice desktop on`), but the managed file is the administrator's,
// root-owned, and splice never writes it. Once that file exists Claude Code takes it as the only policy source and
// drops the policy Desktop hands each Code session, so the file also needs `parentSettingsBehavior` set to `"merge"`
// (Desktop warns in every Code session without it). `--print` (the default) shows the file as it should read, the
// current one with both keys set (`processWrapper` at `<state dir>/desktop-launch`), and how to put it in place on this
// platform; `--check` says whether it reads so now (exit 0), what is missing (exit 1), or, where the wrapper works but
// `parentSettingsBehavior` is not `"merge"`, what Desktop will do and how to fix it (exit 0: the Code tab does start
// through splice, so the check does not call it broken).
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE
import splice.client.PROCESS_WRAPPER_KEY
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The wrapper setting for the splice [env] names, said on [out], with what goes wrong on [err]: Claude Code's managed
 *  settings file on [osName], or [managedFile] where one is given. */
internal class DesktopWrapper(
    private val env: EnvReader,
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    osName: String = System.getProperty(OS_NAME).orEmpty(),
    managedFile: Path? = null,
) {
    private val windows = osName.lowercase().startsWith("windows")
    private val managed: Path = managedFile ?: ManagedSettings(osName).file

    private val launch: Path get() = DesktopPrograms(StatePaths(envReader = env).stateDir).launch

    fun run(args: List<String>): Int = when (args) {
        emptyList<String>(), listOf(WRAPPER_PRINT_FLAG) -> print()
        listOf(WRAPPER_CHECK_FLAG) -> check()
        else -> {
            err.line("usage: splice desktop wrapper [$WRAPPER_PRINT_FLAG | $WRAPPER_CHECK_FLAG]")
            LAUNCH_USAGE_EXIT
        }
    }

    private fun print(): Int {
        val settings = current(warn = true)
        val wanted = JsonObject(
            settings.orEmpty() + (WRAPPER_KEY to JsonPrimitive(launch.toString())) +
                (PARENT_KEY to JsonPrimitive(PARENT_MERGE)),
        )
        // A file that is there but is no JSON object can't be shown with its other keys: it is added to by hand, never
        // replaced by a copy that leaves them out.
        val byHand = Files.isRegularFile(managed) && settings == null
        out.line(
            when {
                byHand -> "$managed exists and splice can't read it, so add these two keys to it yourself:"
                settings != null -> "$managed holds settings already. Add these two keys to it, so it reads:"
                else -> "$managed does not exist yet. Create it so it reads:"
            },
        )
        out.line(PRETTY.encodeToString(JsonObject.serializer(), wanted))
        out.line("")
        out.line("$WRAPPER_KEY starts Claude Desktop's Code tab through splice. $PARENT_KEY \"$PARENT_MERGE\" is there")
        out.line("so Claude Desktop's own policy still applies to the Code sessions it starts, and Desktop does not")
        out.line("warn in each of them.")
        out.line("")
        if (byHand) {
            out.line("The file is the administrator's, and splice never writes it.")
        } else {
            out.line("The file is the administrator's, and splice never writes it.")
            out.line("To put it in place, save the text above as")
            if (windows) {
                out.line("managed-settings.json and, in a terminal run as administrator, run:")
                out.line("  New-Item -ItemType Directory -Force '${managed.parent}'")
                out.line("  Copy-Item managed-settings.json '$managed'")
            } else {
                out.line("managed-settings.json and run:")
                out.line("  sudo mkdir -p '${managed.parent}' && sudo install -m 0644 managed-settings.json '$managed'")
            }
        }
        out.line("Then quit Claude Desktop entirely and reopen it.")
        out.line("`splice desktop wrapper --check` says whether it took.")
        if (!Files.isExecutable(launch)) out.line("$launch is not laid yet: run `splice desktop on` first.")
        return 0
    }

    private fun check(): Int {
        val settings = current(warn = false)
        val named = JsonScalars.str(settings, WRAPPER_KEY)
        val problem = when {
            !Files.isExecutable(launch) -> "$launch is not laid: run `splice desktop on`"
            !Files.isRegularFile(managed) -> "$managed does not exist: `splice desktop wrapper` shows what it needs"
            named == null -> "$managed names no $WRAPPER_KEY: `splice desktop wrapper` shows what it needs"
            named != launch.toString() -> "$managed names $WRAPPER_KEY $named, not $launch"
            else -> null
        }
        if (problem != null) {
            out.line("splice: not set up: $problem")
            return 1
        }
        out.line("splice: Claude Code's $WRAPPER_KEY is $launch")
        if (JsonScalars.str(settings, PARENT_KEY) != PARENT_MERGE) {
            // The Code tab does start through splice, so this is no failure: exit 0 and say what it costs.
            out.line(
                "splice: but $managed does not set $PARENT_KEY to \"$PARENT_MERGE\", so Claude Desktop's own policy " +
                    "is not applied to the Code sessions it starts, and Desktop warns in every one of them. Add " +
                    "\"$PARENT_KEY\": \"$PARENT_MERGE\" to it (`splice desktop wrapper` shows the file), then quit " +
                    "Claude Desktop entirely and reopen it.",
            )
        }
        return 0
    }

    /** The managed settings as they are, or null where there are none or they are not a JSON object; with [warn], a
     *  file that is there but can't be read as one is said on [err], since its other keys are then left out. */
    private fun current(warn: Boolean): JsonObject? {
        if (!Files.isRegularFile(managed)) return null
        val text = try {
            Files.readString(managed)
        } catch (e: IOException) {
            val why = SafeFailureText.render(e)
            if (warn) err.line("splice: $managed can't be read ($why); its other keys are not shown")
            return null
        }
        val settings = JsonScalars.objectOrNull(Json, text)
        if (settings == null && warn) err.line("splice: $managed is not a JSON object; its other keys are not shown")
        return settings
    }
}

private val PRETTY = Json { prettyPrint = true }

// The managed settings this verb sets (Claude Code's own names, kept with its managed settings), and its two flags.
private const val WRAPPER_KEY = PROCESS_WRAPPER_KEY
private const val PARENT_KEY = PARENT_SETTINGS_KEY
private const val PARENT_MERGE = PARENT_SETTINGS_MERGE
private const val WRAPPER_PRINT_FLAG = "--print"
private const val WRAPPER_CHECK_FLAG = "--check"
