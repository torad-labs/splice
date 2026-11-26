// NEW: desktop-antigravity. `splice desktop wrapper [--print | --check]`: the settings Claude Desktop's Code tab needs
// from outside splice. Desktop starts a Code tab session through a process wrapper only when Claude Code's managed
// settings name one (`processWrapper`); splice's Desktop configuration already turns the wrapper on
// (`claudeCodeProcessWrapperEnabled`, written by `splice desktop on`), but the managed file is the administrator's,
// root-owned, and splice never writes it. Once that file exists Claude Code takes it as the only policy source and
// drops the policy Desktop hands each Code session, unless it sets `parentSettingsBehavior` to `"merge"`: Desktop
// warns in every Code session without it. For each of the two keys every line says which of three things is true:
// already right, absent and to be added, or set to a value and to be changed. `--print` (the default) says so for the
// file that is there, and prints the whole file only where there is none; it never offers a copy of a file that
// exists, whose owner, mode and other keys a copy would lose, and never prints what such a file holds. `--check` says
// whether it reads so now (exit 0), what is missing (exit 1) or, where the wrapper works but the file does not say
// "merge" (exit 0, since the Code tab does start through splice), what Desktop will do. A file that is there and could
// not be read (no permission, or no JSON object) is neither right nor wrong: both say so for both keys, and `--check`
// exits 1 because it cannot confirm. Only the one file is read: a drop-in or system policy that Claude Code also reads
// is said to be unread, never called missing, and a machine with no main file but such a source says so too.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE
import splice.client.PROCESS_WRAPPER_KEY
import splice.client.ParentSetting
import splice.client.PolicyDocument
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
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
    private val file: Path = managed.file

    private val launch: Path get() = DesktopPrograms(StatePaths(envReader = env).stateDir).launch
    private val launchJson: JsonElement get() = JsonPrimitive(launch.toString())
    private val mergeJson: JsonElement = JsonPrimitive(PARENT_SETTINGS_MERGE)

    fun run(args: List<String>): Int = when (args) {
        emptyList<String>(), listOf(WRAPPER_PRINT_FLAG) -> print()
        listOf(WRAPPER_CHECK_FLAG) -> check()
        else -> {
            err.line("usage: splice desktop wrapper [$WRAPPER_PRINT_FLAG | $WRAPPER_CHECK_FLAG]")
            LAUNCH_USAGE_EXIT
        }
    }

    private fun print(): Int {
        val document = managed.fileDocument()
        val settings = document?.settings
        val todo = when {
            document == null -> true.also { printNew() }
            settings == null -> true.also { printByHand() }
            else -> printExisting(document, settings)
        }
        if (todo) {
            out.line("Then quit Claude Desktop entirely and reopen it.")
            out.line("`splice desktop wrapper --check` says whether it took.")
        }
        if (!Files.isExecutable(launch)) out.line("$launch is not laid yet: run `splice desktop on` first.")
        return 0
    }

    /** No file: the whole file, and the commands that put it in place. */
    private fun printNew() {
        val wanted = JsonObject(mapOf(WRAPPER_KEY to launchJson, PARENT_KEY to mergeJson))
        out.line("$file does not exist yet. Create it so it reads:")
        out.line(PRETTY.encodeToString(JsonObject.serializer(), wanted))
        out.line("")
        why()
        out.line("")
        out.line("It is the administrator's, and splice never writes it. To put it in place, save the text above as")
        if (windows) {
            out.line("managed-settings.json and, in a terminal run as administrator, run:")
            out.line("  New-Item -ItemType Directory -Force '${file.parent}'")
            out.line("  Copy-Item managed-settings.json '$file'")
        } else {
            out.line("managed-settings.json and run:")
            out.line("  sudo mkdir -p '${file.parent}' && sudo install -m 0644 managed-settings.json '$file'")
        }
        unread(missing = true)
    }

    /** A file that is there but is no JSON object: its keys are unknown, so the person is told what it needs. */
    private fun printByHand() {
        out.line("$file exists but splice could not read it (no permission, or it is not a JSON object), so it")
        out.line("cannot say which of these the file already has.")
        out.line("Edit it in place, ${editor()}; make sure it has both keys:")
        out.line("  \"$WRAPPER_KEY\": $launchJson")
        out.line("  \"$PARENT_KEY\": $mergeJson")
        out.line("")
        why()
        unread()
    }

    /** A file that is there and is a JSON object: for each key, already right, to be added, or to be changed. False
     *  where both are right and there is nothing to do. */
    private fun printExisting(document: PolicyDocument, settings: JsonObject): Boolean {
        val parent = document.parentSetting()
        val wrapperRight = settings[WRAPPER_KEY] == launchJson
        if (wrapperRight && parent.merges) {
            out.line("$file already has both keys right: $WRAPPER_KEY is $launchJson and $PARENT_KEY is $mergeJson.")
            out.line("There is nothing to add. `splice desktop wrapper --check` confirms it.")
            unread()
            return false
        }
        out.line("$file holds settings already. Do not replace it with a copy: that would lose its")
        out.line("owner, its mode and its other keys. Edit it in place, ${editor()}, and add or change only these:")
        out.line("  $WRAPPER_KEY: ${change(WRAPPER_KEY, settings[WRAPPER_KEY], launchJson)}")
        out.line("  $PARENT_KEY: ${change(PARENT_KEY, settings[PARENT_KEY], mergeJson)}")
        out.line("")
        if (!parent.merges) why()
        if (!parent.absent && !parent.merges) {
            out.line("It is set to ${parent.shown} there. Changing it puts Claude Desktop's limits under the")
            out.line("administrator's own for the Code sessions Desktop starts; leaving it keeps the administrator's")
            out.line("settings the only policy, and Desktop then warns in every Code session. That is the")
            out.line("administrator's choice.")
        }
        unread()
        return true
    }

    /** What one key needs: already [wanted], to be added, or set to another value and to be changed. */
    private fun change(key: String, found: JsonElement?, wanted: JsonElement): String = when {
        found == null || found is JsonNull -> "add \"$key\": $wanted"
        found == wanted -> "already $wanted; leave it"
        else -> "set to $found; change it to $wanted"
    }

    private fun why() {
        out.line("$WRAPPER_KEY starts Claude Desktop's Code tab through splice. $PARENT_KEY \"$PARENT_MERGE\" is there")
        out.line("so the limits in Claude Desktop's own policy still apply to the Code sessions it starts, and Desktop")
        out.line("does not warn in each of them.")
    }

    private fun editor(): String =
        if (windows) "in an editor run as administrator" else "as an administrator (for example `sudoedit '$file'`)"

    /** The places Claude Code also reads that hold something, said where splice read only [file]. */
    private fun unread(missing: Boolean = false) {
        val others = managed.otherSources()
        if (others.isEmpty()) return
        if (missing) {
            out.line("Claude Code also reads ${others.joinToString(", ")}, which splice did not read: it may set")
            out.line("these keys already, and the highest source that sets $PARENT_KEY wins over this file.")
        } else {
            out.line("Claude Code also reads ${others.joinToString(", ")}, which splice did not read:")
            out.line("$PARENT_KEY may be set there, and the highest source that sets it wins over")
            out.line("$file.")
        }
    }

    private fun check(): Int {
        val document = managed.fileDocument()
        val problem = problem(document)
        if (problem != null) {
            out.line("splice: not set up: $problem")
            if (document == null) unreadCheck()
            return 1
        }
        // The Code tab starts through splice in every case below, so none is a failure: all exit 0, and each first line
        // says the whole state of the file.
        said(document?.parentSetting() ?: ParentSetting())
        unreadCheck(overrides = true)
        return 0
    }

    /** What keeps the wrapper from starting through splice by the file [document], or null where nothing does. */
    private fun problem(document: PolicyDocument?): String? {
        val settings = document?.settings
        val named = JsonScalars.str(settings, WRAPPER_KEY)
        return when {
            !Files.isExecutable(launch) -> "$launch is not laid: run `splice desktop on`"
            document == null -> "$file does not exist: `splice desktop wrapper` shows what it needs"
            settings == null ->
                "$file could not be read (no permission, or it is not a JSON object), so splice cannot say what " +
                    "it holds for $WRAPPER_KEY or $PARENT_KEY: read it as an administrator, and `splice desktop " +
                    "wrapper` says what it needs"
            named == null -> "$file names no $WRAPPER_KEY: `splice desktop wrapper` shows what it needs"
            named != launch.toString() -> "$file names $WRAPPER_KEY $named, not $launch"
            else -> null
        }
    }

    /** The places Claude Code also reads that hold something, said where splice read only [file]: that the file is
     *  missing, or that they may win over it. */
    private fun unreadCheck(overrides: Boolean = false) {
        val others = managed.otherSources()
        if (others.isEmpty()) return
        val list = others.joinToString(", ")
        if (overrides) {
            out.line("splice: Claude Code also reads $list, which splice did not read; a setting")
            out.line("splice: there may win over $file's.")
        } else {
            out.line("splice: Claude Code reads $list, which splice did not read: it may set these keys, and Desktop")
            out.line("splice: warns in every Code session unless one sets $PARENT_KEY to $mergeJson.")
        }
    }

    /** What the file says of `parentSettingsBehavior` where the wrapper works: the whole state in the first line. */
    private fun said(parent: ParentSetting) {
        val state = "splice: Claude Code's $WRAPPER_KEY is $launch"
        val dropped = "Desktop warns in every Code session and its limits for Code are not applied"
        when {
            parent.merges -> out.line("$state and $PARENT_KEY is $mergeJson in $file")
            parent.absent -> {
                out.line("$state, but $file does not set $PARENT_KEY: $dropped")
                out.line("splice: add \"$PARENT_KEY\": $mergeJson to it (`splice desktop wrapper` shows what to put")
                out.line("splice: where), then quit Claude Desktop entirely and reopen it.")
            }
            else -> {
                out.line("$state, but $file sets $PARENT_KEY to ${parent.shown}: $dropped")
                out.line("splice: changing it to $mergeJson puts Claude Desktop's limits under the administrator's own")
                out.line("splice: for the Code sessions Desktop starts, and stops the warning; that is the")
                out.line("splice: administrator's choice.")
            }
        }
    }
}

private val PRETTY = Json { prettyPrint = true }

// The managed settings this verb sets (Claude Code's own names, kept with its managed settings), and its two flags.
private const val WRAPPER_KEY = PROCESS_WRAPPER_KEY
private const val PARENT_KEY = PARENT_SETTINGS_KEY
private const val PARENT_MERGE = PARENT_SETTINGS_MERGE
private const val WRAPPER_PRINT_FLAG = "--print"
private const val WRAPPER_CHECK_FLAG = "--check"
