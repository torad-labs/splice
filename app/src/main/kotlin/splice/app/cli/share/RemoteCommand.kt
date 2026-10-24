// NEW: LAN share (docs/specs/lan-share.md; Marcos, 2026-10-22). `splice remote`, the using machine's side: find the
// machines on this network that share heads (RemoteDiscovery), ask per machine, and import it (RemoteImport): join,
// a `[remotes.<name>]` entry, and each of its heads regenerated here with every turn relayed there and run on its
// logins. Phase 2 adds `list`, `sync` (the rows each head serves there now) and `remove` (the machine, its heads,
// their command links and its token). Importing a machine again renews its token, which is how a revoked one returns.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.DEFAULT_SHARE_PORT
import splice.core.util.EnvReader
import splice.share.ShareHostName
import splice.share.remote.RemoteDiscovery
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteTrust
import splice.terminal.ConfirmPrompt
import splice.terminal.ConsoleConfirm

internal const val REMOTE_USAGE = "[scan] | add <host[:port]> | list | sync [<machine>] | remove <machine>"

internal class RemoteCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val confirm: ConfirmPrompt = ConsoleConfirm(),
) {
    // A first contact: discovery reads what each machine offers before any is pinned (RemoteTrust).
    private val http = RemoteHttp(RemoteTrust(null, "a sharing machine"))
    private val machines = RemoteMachines(out, err, env)

    /** How many arguments each verb takes after its name. */
    private val arity = mapOf("scan" to 0..0, "add" to 1..1, "list" to 0..0, "sync" to 0..1, "remove" to 1..1)

    suspend fun run(args: List<String>): Int {
        val verb = args.firstOrNull() ?: "scan"
        val rest = args.drop(1)
        if (rest.size !in arity[verb] ?: IntRange.EMPTY) return usage()
        val arg = rest.firstOrNull()
        return when (verb) {
            "scan" -> scan()
            "add" -> addHost(arg.orEmpty())
            "list" -> machines.list()
            "sync" -> machines.sync(arg)
            else -> machines.remove(arg.orEmpty())
        }
    }

    private suspend fun scan(): Int {
        out.line("${DIM}looking for machines that share splice heads on your networks…$RESET")
        val self = ShareHostName().short()
        val found = RemoteDiscovery(http).scan(DEFAULT_SHARE_PORT).filterNot { it.info.server == self }
        if (found.isEmpty()) {
            out.line("no machine on your networks shares heads.")
            out.line("  on the machine with the logins: ${CYAN}splice share on$RESET")
            out.line("  across Tailscale or another network: ${CYAN}splice remote add <host>$RESET")
            return PeerExit.REFUSED
        }
        var imported = false
        found.forEach { machine ->
            describe(machine)
            val wanted = confirm("Use ${machine.info.server}'s heads here?", true)
            if (wanted) imported = importer().import(machine) || imported
        }
        return if (imported) PeerExit.DONE else PeerExit.REFUSED
    }

    private suspend fun addHost(target: String): Int {
        val (host, port) = RemoteAddress.of(target) ?: return usage()
        val info = machines.info(host, port) ?: return PeerExit.REFUSED
        val machine = RemoteFound(host, port, info)
        describe(machine)
        val imported = confirm("Use ${info.server}'s heads here?", true) && importer().import(machine)
        return if (imported) PeerExit.DONE else PeerExit.REFUSED
    }

    private fun describe(machine: RemoteFound) {
        out.line("")
        out.line("$BOLD${machine.info.server}$RESET ${DIM}at ${machine.address}, splice ${machine.info.version}$RESET")
        machine.info.heads.forEach { head ->
            val models = head.models.joinToString(", ") { it.label }
            out.line("  ${head.command.padEnd(REMOTE_COMMAND_PAD)} $DIM$models$RESET")
        }
    }

    private fun importer(): RemoteImport = RemoteImport(out, err, env, confirm)

    private fun usage(): Int {
        err.line("usage: splice remote $REMOTE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

// why: the command column of a listing: `claude-` and a head name of up to eleven characters line their models
// up, and a longer command pushes only its own row's models to the right.
internal const val REMOTE_COMMAND_PAD = 18
