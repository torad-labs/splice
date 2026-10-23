// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). `splice share`, the sharing machine's
// side: turn `[share]` on or off in splice.toml (through TopologyWriter, which refuses an edit that brings a
// finding and backs the file up first) and restart so the listener follows; list the machines that joined;
// revoke one. The listener itself is the daemon's (ShareServer).
package splice.app.cli.share

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.peer.PeerExit
import splice.app.share.ShareClients
import splice.app.share.ShareState
import splice.core.config.StatePaths
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

internal const val SHARE_USAGE = "on [--port N] | off | status | revoke <machine>"

internal class ShareCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val clients = ShareClients(ShareState(StatePaths()).clientsFile)
    private val path = TopologyLoader.configPath(env)

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

    private fun loaded(): Topology? =
        Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(path)) }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                err.line("splice share: $path does not load: ${SafeFailureText.render(failure)}")
                null
            },
        )

    private fun saved(wanted: Topology): Boolean {
        val writer = TopologyWriter(path, StatePaths().configBackupsDir, TopologyParse(TopologyLoader::parse))
        val result = Cancellables.runCatchingCancellable { writer.write(wanted) }.getOrElse { failure ->
            err.line("splice share: $path was not changed: ${SafeFailureText.render(failure)}")
            return false
        }
        if (result is TopologyWriteResult.Refused) result.findings.forEach { err.line("splice share: ${it.text()}") }
        return result !is TopologyWriteResult.Refused
    }

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

    private fun status(): Int {
        val topology = loaded() ?: return PeerExit.REFUSED
        val share = topology.share
        if (share.enabled) {
            val state = if (listening(share.port)) "${GREEN}listening$RESET" else "not listening (is the daemon up?)"
            out.line("sharing is on, port ${share.port}: $state")
            out.line("  heads     ${share.sharedHeads(topology).joinToString(", ").ifEmpty { "none" }}")
        } else {
            out.line("sharing is off. ${CYAN}splice share on$RESET offers this machine's heads to your other machines.")
        }
        val joined = clients.all()
        if (joined.isEmpty()) out.line("  machines  none joined")
        joined.forEach { machine ->
            val name = machine.name.padEnd(MACHINE_NAME_PAD)
            out.line("  machine   $name from ${machine.address}, joined ${machine.joinedAt}")
        }
        return PeerExit.DONE
    }

    private fun revoke(name: String): Int {
        if (!clients.revoke(name)) {
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
