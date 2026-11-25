// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — what the person gave a launch, read back from the
// arguments its client runs with. A relaunch starts a session on the command it was started with, and the launch that
// command makes adds splice's own words again, so only the person's are carried: splice's would arrive twice, and a
// hub file named for the old launch's process is swept once that process is gone.
//
// WHAT A LAUNCH ADDS, in the order LaunchService.launchOutcome writes it after the binary: --dangerously-skip-
// permissions when the launch asked for it (kept, since the session was started with it); the --settings overlay of a
// launch through the wrapped `claude`, or splice's own mark on macOS (LaunchMarker); then the MCP hub's file for the
// launch, or a wrapped launch's own file, as one --mcp-config in splice's state, with a --plugin-dir-no-mcp for each
// plugin the hub runs. Everything after that is the person's, and so is the --effort a launch adds there (Oct 28): a
// relaunch starts at the effort its session started at, and adds none of its own beside it. A launch of `claude` on the
// door puts the file of the daemon's own servers after the person's words, before their `--` (Nov 25): splice's too.
package splice.launch.recipe

import splice.client.ClaudeArgv
import splice.client.ClaudeOption
import splice.client.mcp.HUB_PLUGIN_FLAG
import splice.client.mcp.MCP_CONFIG_FLAG
import splice.sessions.registry.LaunchMarker
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val DANGEROUSLY_SKIP = "--dangerously-skip-permissions"

/** The words a launch's client was given by the person, after what splice's launch put before them. */
public object LaunchRecipeArgs {
    private val marked = "\"${LaunchMarker.SPLICE_LAUNCH_KEY}\":true"

    /** The person's words among [arguments], the client's arguments after its binary: what follows the launch's own
     *  --settings ([wrapped]: it went through the wrapped `claude`, whose overlay that is) and each --mcp-config file
     *  in splice's [state], with the plugin folders after it. A --dangerously-skip-permissions the launch put first
     *  stays. */
    public fun callerArgs(arguments: List<String>, state: Path, wrapped: Boolean): List<String> {
        val skip = arguments.take(1).filter { it == DANGEROUSLY_SKIP }
        var at = skip.size
        val settings = arguments.getOrNull(at + 1)
        // the launch's own overlay: a wrapped launch's, whatever it holds, or the mark splice adds on macOS
        val ours = settings != null && (wrapped || marked in settings)
        if (arguments.getOrNull(at) == LaunchMarker.SETTINGS_FLAG && ours) at += 2
        // each of the launch's own: the hub's file with its plugin folders, then the file of splice's own servers
        while (arguments.getOrNull(at) == MCP_CONFIG_FLAG && inState(arguments.getOrNull(at + 1), state)) {
            at += 2
            while (arguments.getOrNull(at) == HUB_PLUGIN_FLAG && at + 1 < arguments.size) at += 2
        }
        return skip + withoutOwnFiles(arguments.drop(at), state)
    }

    /** [words] without the `--mcp-config` files in splice's [state] among their options: the file of the daemon's own
     *  servers, which a launch of `claude` on the door puts after the person's words. A flag leaves with its values
     *  only when every one is splice's, so a file of the person's after it keeps its flag. The launch the relaunch
     *  makes names splice's file again while its mode is on, and names none once it is off. */
    private fun withoutOwnFiles(words: List<String>, state: Path): List<String> {
        val dropped = ClaudeArgv.options(words).each.filter { it.name == MCP_CONFIG_FLAG }
            .flatMap { ownWords(words, it, state) }.toSet()
        return words.filterIndexed { at, _ -> at !in dropped }
    }

    /** Where [option], a `--mcp-config`, names files in splice's [state] among [words]: those values, and the flag
     *  too when it names no other. */
    private fun ownWords(words: List<String>, option: ClaudeOption, state: Path): List<Int> {
        if (option.flag != option.name) {
            return listOf(option.at).filter { inState(option.flag.substringAfter('='), state) }
        }
        val values = (option.at + 1 until option.next).toList()
        val ours = values.filter { inState(words[it], state) }
        return if (ours.isNotEmpty() && ours.size == values.size) ours + option.at else ours
    }

    /** Whether [value] is a file in splice's [state], where a launch writes its MCP files; any other is the person's. */
    private fun inState(value: String?, state: Path): Boolean = try {
        val under = state.toAbsolutePath().normalize()
        value != null && Path.of(value).let { it.isAbsolute && it.normalize().startsWith(under) }
    } catch (_: InvalidPathException) {
        false
    }
}
