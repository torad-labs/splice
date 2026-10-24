// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). `splice share`, the server's side: turn
// `[share]` on or off in splice.toml and restart so the listener follows, and print, for each command this machine
// offers, the address another machine adds it by. What is offered is the listener's own answer, asked on loopback, so
// the list is the daemon's, never a guess from the file.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.ShareConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.share.ShareAddresses
import splice.share.ShareDirectory
import splice.share.ShareDirectoryRead
import splice.share.ShareInfo
import splice.upstream.codemode.ProcessDispatchers

internal const val SHARE_USAGE = "on [--port N] | off | status"

internal class ShareCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    env: EnvReader = EnvReader(System::getenv),
) {
    private val file = ShareTopologyFile(err, SHARE_VERB, env)
    private val restart = ShareRestart(out, err, env, SHARE_VERB)

    suspend fun run(args: List<String>): Int {
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            "on" -> on(rest)
            "off" -> if (rest.isEmpty()) switched(false, null) else usage()
            null, "status" -> if (rest.isEmpty()) status() else usage()
            else -> usage()
        }
    }

    private suspend fun on(rest: List<String>): Int = when {
        rest.isEmpty() -> switched(true, null)
        rest.size == 2 && rest[0] == "--port" -> rest[1].toIntOrNull()?.let { switched(true, it) } ?: usage()
        else -> usage()
    }

    private suspend fun switched(on: Boolean, port: Int?): Int {
        val current = file.loaded() ?: return PeerExit.REFUSED
        val wanted = current.copy(share = current.share.copy(enabled = on, port = port ?: current.share.port))
        if (!file.saved(wanted)) return PeerExit.REFUSED
        out.line("$GREEN✓$RESET sharing ${if (on) "on, port ${wanted.share.port}" else "off"} in ${file.path}")
        return when {
            !restart.followed(wanted) -> PeerExit.REFUSED
            on && restart.up(wanted) -> status()
            else -> PeerExit.DONE
        }
    }

    private suspend fun status(): Int {
        val share = (file.loaded() ?: return PeerExit.REFUSED).share
        if (!share.enabled) {
            out.line("sharing is off. ${CYAN}splice share on$RESET lets your other machines run this one's commands.")
            return PeerExit.DONE
        }
        return when (val read = ShareDirectory(ProcessDispatchers().io()).read("http://127.0.0.1:${share.port}")) {
            is ShareDirectoryRead.Read -> offered(share, read.info)
            is ShareDirectoryRead.Refused -> {
                err.line("$SHARE_VERB: sharing is on in ${file.path}, but nothing here answers on it: ${read.why}")
                err.line("  is splice running? ${CYAN}splice restart$RESET")
                PeerExit.REFUSED
            }
        }
    }

    /** What [info] offers, each command at the address another machine on this LAN adds it by. */
    private fun offered(share: ShareConfig, info: ShareInfo): Int {
        val reach = ShareAddresses(share, info.machine, LogSink { err.line(it.trimEnd()) }).reach()
        val server = reach.server ?: run {
            err.line("$SHARE_VERB: ${info.machine} has no address on a LAN, so no other machine can reach it")
            return PeerExit.REFUSED
        }
        out.line("sharing is on: $BOLD$server$RESET, on ${info.machine}'s LAN")
        if (info.commands.isEmpty()) out.line("  no command here holds a login of its own to offer")
        info.commands.forEach { offer ->
            val state = if (offer.running) "" else "  ${DIM}not running$RESET"
            out.line("  ${offer.command.padEnd(COMMAND_PAD)} $server/${offer.command}$state")
            val logins = offer.logins.joinToString(", ").ifEmpty { "its one login" }
            val models = offer.models.joinToString(", ")
            out.line("  ${"".padEnd(COMMAND_PAD)} ${DIM}logins: $logins · models: $models$RESET")
        }
        out.line("on another machine: ${CYAN}splice remote add <address>$RESET, or every one at once:")
        out.line("  ${CYAN}splice remote add $server$RESET")
        out.line("${DIM}an address may end in ?auth=<login> to run on that login,$RESET")
        out.line("${DIM}and in ?models=<id>,<id> to show only those models$RESET")
        val others = reach.addresses - server
        if (others.isNotEmpty()) out.line("${DIM}by address: ${others.joinToString(", ")}$RESET")
        return PeerExit.DONE
    }

    private fun usage(): Int {
        err.line("usage: splice share $SHARE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

private const val SHARE_VERB = "splice share"

// why: the command column of `splice share status`: `claude-` and a head name of up to eleven characters line their
// addresses up, and a longer command pushes only its own row.
private const val COMMAND_PAD = 18
