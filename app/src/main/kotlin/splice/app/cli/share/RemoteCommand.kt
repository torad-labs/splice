// The door on the network (docs/specs/door-network.md). `splice remote`, a node's side: the commands this machine runs
// as Claude Code pointed at another machine's door, or at a team server's commands. Each is one `[remote.<command>]`
// line in splice.toml holding the address `splice remote add` checked, and a command link like every other; nothing
// else is kept, no credential and no copy of the other machine's models. `add` checks the address against the server
// first (RemoteAdd), `remove` takes a command and its link out, and the bare verb lists them, each with whether its
// server answers now. On a team's server (docs/specs/server-mode.md, F7) `refresh` follows the commands it adds and
// drops (RemoteRefresh), and `set <command> --window` pins a context window on this laptop or gives the server's back
// (RemoteWindow); the list (RemoteList) shows a pinned window.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.CYAN
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallResult
import splice.share.LaptopLogins
import splice.share.ServerProfiles
import splice.share.ShareDirectory
import splice.upstream.codemode.ProcessDispatchers

internal const val REMOTE_USAGE = "[list] | add <address> [--as <command>] | refresh <server> | " +
    "set <command> --window <tokens>|server | remove <command>"

internal class RemoteCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val file = ShareTopologyFile(err, REMOTE_VERB, env)
    private val restart = ShareRestart(out, err, env, REMOTE_VERB)
    private val logins = LaptopLogins(env).logins
    private val asks = ProcessDispatchers().io().let { io ->
        RemoteAsks(ShareDirectory(io, logins), ServerProfiles(io, logins), logins)
    }
    private val install = InstallCommand(out, err)
    private val relays = FirstBuildRelays(out, err, env)
    private val names = RemoteNames(err, relays) { install.runsShim(it, env) }

    suspend fun run(args: List<String>): Int {
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            null, "list" -> if (rest.isEmpty()) RemoteList(out, file, asks).list() else usage()
            "add" -> add(rest)
            "refresh" -> refresh(rest)
            "set" -> set(rest)
            "remove" -> rest.singleOrNull()?.let(::remove) ?: usage()
            else -> usage()
        }
    }

    /** `add <address>` or `add <address> --as <command>`. */
    private suspend fun add(rest: List<String>): Int {
        val named = rest.size == AS_ARGS && rest[1] == "--as"
        if (rest.size != 1 && !named) return usage()
        val add = RemoteAdd(out, file, asks, relays, names)
        val added = add.add(rest[0], if (named) rest[2] else null) ?: return PeerExit.REFUSED
        // Every command is linked, even after one is refused, so each refusal is said.
        val whole = added.commands.map { link(it, added.machine) }.all { it } && added.complete
        val followed = restart.followed(added.topology)
        return if (followed && whole) PeerExit.DONE else PeerExit.REFUSED
    }

    /** `refresh <server>`: the commands the server started offering linked, the ones it dropped unlinked, each
     *  said. */
    private suspend fun refresh(rest: List<String>): Int {
        val server = rest.singleOrNull() ?: return usage()
        val refreshed = RemoteRefresh(out, file, asks, names).refresh(server) { install.uninstall(it, env) }
            ?: return PeerExit.REFUSED
        refreshed.unlinked.forEach { out.line("$GREEN✓$RESET $CYAN$it$RESET unlinked: $server no longer offers it") }
        // Every command is linked, even after one is refused, so each refusal is said.
        val whole = refreshed.linked.map { link(it, server) }.all { it } && refreshed.complete
        val changed = refreshed.linked.isNotEmpty() || refreshed.unlinked.isNotEmpty()
        val followed = !changed || restart.followed(refreshed.topology)
        return if (followed && whole) PeerExit.DONE else PeerExit.REFUSED
    }

    /** `set <command> --window <tokens>|server`. */
    private fun set(rest: List<String>): Int {
        if (rest.size != SET_ARGS || rest[1] != "--window") return usage()
        val pinned = RemoteWindow(out, file, names).set(rest[0], rest[2]) ?: return PeerExit.REFUSED
        return if (!pinned.changed || restart.followed(pinned.topology)) PeerExit.DONE else PeerExit.REFUSED
    }

    /** [command]'s link, to the launch shim as every command's is; false, said, when it was not made. */
    private fun link(command: String, machine: String): Boolean = when (val linked = install.install(command, env)) {
        is InstallResult.Refused -> {
            err.line("$REMOTE_VERB: ${linked.sentence}")
            false
        }
        InstallResult.Declined -> false
        InstallResult.Linked -> {
            out.line("$GREEN✓$RESET $CYAN$command$RESET runs on $machine")
            true
        }
    }

    private fun remove(command: String): Int {
        val topology = file.loaded()?.takeIf { remote(it, command) } ?: return PeerExit.REFUSED
        install.uninstall(command, env)
        val wanted = topology.copy(remote = topology.remote - command)
        if (!file.saved(wanted, topology)) return PeerExit.REFUSED
        out.line("$GREEN✓$RESET $command no longer runs on another machine here")
        return if (restart.followed(wanted)) PeerExit.DONE else PeerExit.REFUSED
    }

    /** Whether [command] is one of [topology]'s remote commands; one that is not is said. */
    private fun remote(topology: Topology, command: String): Boolean = (command in topology.remote).also { found ->
        val listed = "${CYAN}splice remote$RESET"
        if (!found) err.line("$REMOTE_VERB: no command here named '$command' runs on another machine ($listed)")
    }

    private fun usage(): Int {
        err.line("usage: splice remote $REMOTE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

internal const val REMOTE_VERB = "splice remote"

// why: `add <address> --as <command>` is three words after the verb.
private const val AS_ARGS = 3

// why: `set <command> --window <tokens>` is three words after the verb.
private const val SET_ARGS = 3

// why: the command column of `splice remote` and `splice share status`: `claude-` and a head name of up to eleven
// characters line their addresses up, and a longer command pushes only its own row.
internal const val COMMAND_PAD = 18
