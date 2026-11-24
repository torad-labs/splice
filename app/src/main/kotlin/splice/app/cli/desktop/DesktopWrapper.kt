// NEW: desktop-antigravity. `splice desktop wrapper [--print | --check]`: the one setting Claude Desktop's Code tab
// needs from outside splice. Desktop starts a Code tab session through a process wrapper only when Claude Code's
// managed settings name one (`processWrapper`); splice's Desktop configuration already turns the wrapper on
// (`claudeCodeProcessWrapperEnabled`, written by `splice desktop on`), but the managed file is the administrator's,
// root-owned, and splice never writes it. `--print` (the default) shows that file as it should read, the current one
// with `processWrapper` set to `<state dir>/desktop-launch`, and how to put it in place on this platform; `--check`
// says whether it reads so now (exit 0), or what is missing (exit 1).
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
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
        val wanted = JsonObject(current(warn = true).orEmpty() + (WRAPPER_KEY to JsonPrimitive(launch.toString())))
        out.line("$managed should read:")
        out.line(PRETTY.encodeToString(JsonObject.serializer(), wanted))
        out.line("")
        out.line("It is the administrator's, and splice never writes it. To put it in place, save the text above as")
        if (windows) {
            out.line("managed-settings.json and, in a terminal run as administrator, run:")
            out.line("  New-Item -ItemType Directory -Force '${managed.parent}'")
            out.line("  Copy-Item managed-settings.json '$managed'")
        } else {
            out.line("managed-settings.json and run:")
            out.line("  sudo mkdir -p '${managed.parent}' && sudo install -m 0644 managed-settings.json '$managed'")
        }
        out.line("Then quit Claude Desktop entirely and reopen it.")
        out.line("`splice desktop wrapper --check` says whether it took.")
        if (!Files.isExecutable(launch)) out.line("$launch is not laid yet: run `splice desktop on` first.")
        return 0
    }

    private fun check(): Int {
        val named = JsonScalars.str(current(warn = false), WRAPPER_KEY)
        val problem = when {
            !Files.isExecutable(launch) -> "$launch is not laid: run `splice desktop on`"
            !Files.isRegularFile(managed) -> "$managed does not exist: `splice desktop wrapper` shows what it needs"
            named == null -> "$managed names no $WRAPPER_KEY: `splice desktop wrapper` shows what it needs"
            named != launch.toString() -> "$managed names $WRAPPER_KEY $named, not $launch"
            else -> null
        }
        out.line(problem?.let { "splice: not set up: $it" } ?: "splice: Claude Code's $WRAPPER_KEY is $launch")
        return if (problem == null) 0 else 1
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

// The managed setting that names the wrapper, and this verb's two flags.
private const val WRAPPER_KEY = "processWrapper"
private const val WRAPPER_PRINT_FLAG = "--print"
private const val WRAPPER_CHECK_FLAG = "--check"
