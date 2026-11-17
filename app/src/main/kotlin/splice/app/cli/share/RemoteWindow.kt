// NEW: server mode (docs/specs/server-mode.md, F7). `splice remote set <command> --window N|server`: a command another
// machine serves launches with the context window that machine declares for its model, unless this laptop pins one of
// its own. The pin is the command's `window` in splice.toml, so it outlives a daemon restart and `splice remote` lists
// it; `--window server` takes it out and the server's window applies again. A window that is no positive number, or a
// command that does not run on another machine, is refused and nothing changes.
package splice.app.cli.share

import splice.core.terminal.CYAN
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology

internal class RemoteWindow(
    private val out: TerminalOutput,
    private val file: ShareTopologyFile,
    private val names: RemoteNames,
) {
    /** [command]'s window set as [value] says: the file as it now is, with whether it changed; null, said, when it
     *  was refused. */
    fun set(command: String, value: String): Pinned? {
        val bad = "--window takes a positive number of tokens, as in 200000, or `server`, not '$value'"
        val window = wanted(value) ?: return names.refused(bad)
        val topology = file.loaded() ?: return null
        val remote = topology.remote[command]
        val listed = "${CYAN}splice remote$RESET lists the ones that are"
        return if (remote == null) {
            names.refused("'$command' is not a command linked from a server here; $listed")
        } else {
            pinned(topology, command, window.tokens)
        }
    }

    /** The window [value] asks for: a positive number of tokens, or none for `server`; null when it is neither. */
    private fun wanted(value: String): Wanted? = when (value) {
        SERVER_WINDOW -> Wanted(null)
        else -> value.toLongOrNull()?.takeIf { it > 0 }?.let(::Wanted)
    }

    /** [topology] with [command], one of its remote commands, on [window]. */
    private fun pinned(topology: Topology, command: String, window: Long?): Pinned? {
        val remote = topology.remote.getValue(command)
        if (remote.window == window) {
            out.line("$command already ${said(window)}")
            return Pinned(topology, changed = false)
        }
        val wanted = topology.copy(remote = topology.remote + (command to remote.copy(window = window)))
        if (!file.saved(wanted, topology)) return null
        out.line("$GREEN✓$RESET $command now ${said(window)}")
        return Pinned(wanted, changed = true)
    }

    /** A window asked for: [tokens], or null for the server's. */
    private data class Wanted(val tokens: Long?)

    private fun said(window: Long?): String = when (window) {
        null -> "launches with the window its server declares for its model"
        else -> "launches with a window of $window tokens, pinned on this laptop"
    }
}

/** What a set left: the file as it now is, and whether the set [changed] it. */
internal data class Pinned(val topology: Topology, val changed: Boolean)

/** The `--window` value that gives a command its server's window back. */
private const val SERVER_WINDOW = "server"
