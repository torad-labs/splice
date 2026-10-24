// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). `splice remote`, a node's side: the commands
// this machine runs as Claude Code pointed at another machine's heads. Each is one `[remote.<command>]` line in
// splice.toml holding the address `splice share status` printed there, and a command link like every other; nothing
// else is kept, no credential and no copy of the server's models. `add` checks the address against the server first
// (RemoteAdd), `remove` takes a command and its link out, and the bare verb lists them, each with whether its server
// answers now.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallResult
import splice.share.ShareDirectory
import splice.share.ShareDirectoryRead
import splice.upstream.codemode.ProcessDispatchers

internal const val REMOTE_USAGE = "[list] | add <address> [--as <command>] | remove <command>"

internal class RemoteCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val file = ShareTopologyFile(err, REMOTE_VERB, env)
    private val restart = ShareRestart(out, err, env, REMOTE_VERB)
    private val directory = ShareDirectory(ProcessDispatchers().io())
    private val install = InstallCommand(out, err)

    suspend fun run(args: List<String>): Int {
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            null, "list" -> if (rest.isEmpty()) list() else usage()
            "add" -> add(rest)
            "remove" -> rest.singleOrNull()?.let(::remove) ?: usage()
            else -> usage()
        }
    }

    /** `add <address>` or `add <address> --as <command>`. */
    private suspend fun add(rest: List<String>): Int {
        val named = rest.size == AS_ARGS && rest[1] == "--as"
        if (rest.size != 1 && !named) return usage()
        val added = RemoteAdd(out, err, file, directory).add(rest[0], if (named) rest[2] else null)
            ?: return PeerExit.REFUSED
        added.commands.forEach { link(it, added.machine) }
        val followed = restart.followed(added.topology)
        return if (followed && added.complete) PeerExit.DONE else PeerExit.REFUSED
    }

    /** [command]'s link, to the launch shim as every command's is. */
    private fun link(command: String, machine: String) {
        when (val linked = install.install(command, env)) {
            is InstallResult.Refused -> err.line("$REMOTE_VERB: ${linked.sentence}")
            InstallResult.Declined -> Unit
            InstallResult.Linked -> out.line("$GREEN✓$RESET $CYAN$command$RESET runs on $machine")
        }
    }

    private fun remove(command: String): Int {
        val topology = file.loaded()?.takeIf { remote(it, command) } ?: return PeerExit.REFUSED
        install.uninstall(command, env)
        val wanted = topology.copy(remote = topology.remote - command)
        if (!file.saved(wanted)) return PeerExit.REFUSED
        out.line("$GREEN✓$RESET $command no longer runs on another machine here")
        return if (restart.followed(wanted)) PeerExit.DONE else PeerExit.REFUSED
    }

    /** Whether [command] is one of [topology]'s remote commands; one that is not is said. */
    private fun remote(topology: Topology, command: String): Boolean = (command in topology.remote).also { found ->
        val listed = "${CYAN}splice remote$RESET"
        if (!found) err.line("$REMOTE_VERB: no command here named '$command' runs on another machine ($listed)")
    }

    private suspend fun list(): Int {
        val topology = file.loaded() ?: return PeerExit.REFUSED
        if (topology.remote.isEmpty()) {
            out.line("no command here runs on another machine.")
            out.line("  on the machine with the logins: ${CYAN}splice share on$RESET, which prints each address")
            out.line("  then here: ${CYAN}splice remote add <address>$RESET")
            return PeerExit.DONE
        }
        val servers = mutableMapOf<String, ShareDirectoryRead>()
        topology.remote.forEach { (command, remote) ->
            val state = when (val read = RemoteAddresses.read(remote.url)) {
                is RemoteAddressRead.Refused -> "not an address it can run on: ${read.why}"
                is RemoteAddressRead.Read ->
                    state(read.address, servers.getOrPut(read.address.server) { directory.read(read.address.server) })
            }
            out.line("  ${command.padEnd(COMMAND_PAD)} ${remote.url}  $DIM$state$RESET")
        }
        return PeerExit.DONE
    }

    /** Whether [address]'s command runs on its server now, as the server's offer [read] says. */
    private fun state(address: RemoteAddress, read: ShareDirectoryRead): String {
        val info = when (read) {
            is ShareDirectoryRead.Refused -> return read.why
            is ShareDirectoryRead.Read -> read.info
        }
        val offer = info.commands.firstOrNull { it.command == address.head }
            ?: info.commands.firstOrNull { it.head == address.head }
        return when {
            offer == null -> "${info.machine} no longer offers ${address.head}"
            !offer.running -> "not running on ${info.machine}"
            else -> "ready on ${info.machine}"
        }
    }

    private fun usage(): Int {
        err.line("usage: splice remote $REMOTE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

internal const val REMOTE_VERB = "splice remote"

// why: `add <address> --as <command>` is three words after the verb.
private const val AS_ARGS = 3

// why: the command column of `splice remote`: `claude-` and a head name of up to eleven characters line their
// addresses up, and a longer command pushes only its own row.
private const val COMMAND_PAD = 18
