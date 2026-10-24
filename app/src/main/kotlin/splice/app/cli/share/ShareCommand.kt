// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). `splice share`, the sharing machine's
// side: turn `[share]` on or off in splice.toml (through TopologyWriter, which refuses an edit that brings a
// finding and backs the file up first) and restart so the listener follows; list the machines that joined;
// revoke one. The listener itself is the daemon's (ShareServer).
package splice.app.cli.share

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.peer.PeerExit
import splice.core.config.StatePaths
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.SafeFailureText
import splice.share.ShareCertificate
import splice.share.ShareClients
import splice.share.ShareState
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant

internal const val SHARE_USAGE = "on [--port N] | off | status | revoke <machine>"

internal class ShareCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val files = ShareState(StatePaths())
    private val clients = ShareClients(files.clientsFile)
    private val file = ShareTopologyFile(err, "splice share", env)
    private val path = file.path

    fun run(args: List<String>): Int {
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            "on" -> on(rest)
            "off" -> if (rest.isEmpty()) switch(false, null) else usage()
            null, "status" -> if (rest.isEmpty()) status() else usage()
            "revoke" -> rest.singleOrNull()?.let(::revoke) ?: usage()
            else -> usage()
        }
    }

    private fun on(rest: List<String>): Int = when {
        rest.isEmpty() -> switch(true, null)
        rest.size == 2 && rest[0] == "--port" -> rest[1].toIntOrNull()?.let { switch(true, it) } ?: usage()
        else -> usage()
    }

    private fun switch(on: Boolean, port: Int?): Int {
        val current = loaded() ?: return PeerExit.REFUSED
        val share = current.share.copy(enabled = on, port = port ?: current.share.port)
        if (!saved(current.copy(share = share))) return PeerExit.REFUSED
        out.line("$GREEN✓$RESET sharing ${if (on) "on, port ${share.port}" else "off"} in $path")
        return restarted(current.copy(share = share))
    }

    private fun loaded(): Topology? = file.loaded()

    private fun saved(wanted: Topology): Boolean = file.saved(wanted)

    /** The listener opens and closes with the daemon, so a running one restarts to follow the file. */
    private fun restarted(topology: Topology): Int {
        if (!AdminSupport.daemonUp(AdminSupport.controlPort(topology, env))) {
            out.line("  the daemon is not running; it follows this at its next start")
            return PeerExit.DONE
        }
        if (!LifecycleWiring.restart()) {
            err.line("splice share: the restart failed; run ${CYAN}splice restart$RESET")
            return PeerExit.REFUSED
        }
        if (topology.share.enabled) out.line("  ${DIM}on your other machines:$RESET ${CYAN}splice remote$RESET")
        return PeerExit.DONE
    }

    /** A stored instant in this machine's own time (LocalTimeText); one that does not parse is shown as stored. */
    private fun said(instant: String): String =
        Cancellables.runCatchingCancellable { LocalTimeText().at(Instant.parse(instant).epochSecond) }
            .getOrElse { instant }

    private fun turns(count: Int): String = if (count == 1) "1 turn" else "$count turns"

    private fun status(): Int {
        val topology = loaded() ?: return PeerExit.REFUSED
        val share = topology.share
        if (share.enabled) {
            val state = if (listening(share.port)) "${GREEN}listening$RESET" else "not listening (is the daemon up?)"
            out.line("sharing is on, port ${share.port}: $state")
            out.line("  heads     ${share.sharedHeads(topology).joinToString(", ").ifEmpty { "none" }}")
            // What a joining machine printed as pinned, for anyone who wants to compare the two.
            val pin = ShareCertificate(files.certificateFile).fingerprint() ?: "minted at the daemon's next start"
            out.line("  tls       $DIM$pin$RESET")
        } else {
            out.line("sharing is off. ${CYAN}splice share on$RESET offers this machine's heads to your other machines.")
        }
        val joined = clients.all()
        if (joined.isEmpty()) out.line("  machines  none joined")
        val seen = clients.activity.read().associateBy { it.name }
        val today = clients.activity.today()
        joined.forEach { machine ->
            val name = machine.name.padEnd(MACHINE_NAME_PAD)
            // Written by the daemon at most once a minute, so "last seen" and the count can trail by that much.
            val heard = seen[machine.name]
                ?.let { ", last seen ${said(it.lastSeen)}, ${turns(it.turnsOn(today))} today" }
            out.line("  machine   $name from ${machine.address}, joined ${said(machine.joinedAt)}${heard.orEmpty()}")
        }
        return PeerExit.DONE
    }

    private fun revoke(name: String): Int {
        val found = Cancellables.runCatchingCancellable { clients.revoke(name) }.getOrElse { failure ->
            err.line("splice share: nothing was revoked: ${SafeFailureText.render(failure)}")
            return PeerExit.REFUSED
        }
        if (!found) {
            err.line("splice share: no machine named '$name' has joined (see ${CYAN}splice share status$RESET)")
            return PeerExit.REFUSED
        }
        val back = "${CYAN}splice remote$RESET"
        out.line("$GREEN✓$RESET '$name' revoked: its next turn is refused until it runs $back again")
        return PeerExit.DONE
    }

    private fun listening(port: Int): Boolean = Cancellables.runCatchingCancellable {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), LISTENER_PROBE_MS) }
    }.isSuccess

    private fun usage(): Int {
        err.line("usage: splice share $SHARE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

// why: the machine column of `splice share status`: a host name of up to sixteen characters lines up the
// address and the join time beside it, and a longer name pushes only its own row.
private const val MACHINE_NAME_PAD = 16

// why: a connect to this machine's own loopback answers in well under a millisecond when the listener is up,
// so half a second only bounds a stalled stack and keeps `splice share status` instant either way.
private const val LISTENER_PROBE_MS = 500
