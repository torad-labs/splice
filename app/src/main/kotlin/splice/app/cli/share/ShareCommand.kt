// The door on the network (docs/specs/door-network.md). `splice share`, the box's side: turn `[share]` on or off in
// splice.toml and restart so the listener follows, and print the door another machine's `claude` runs on: its address,
// how many models it serves, the one `splice remote add http://<box>` to run there, and the variables a Claude Code
// without splice runs on it with. Since share-door-only (Nov 19, 2026) a machine shares its door alone, never one
// command at an address of its own, so nothing per command is printed. What is printed is the listener's own answer,
// asked on loopback: `GET /` for the machine's name, which also tells this machine's listener from another program on
// the port, then the door's launch. A door still starting is said as that. A port another program here holds is
// refused before the file changes: the listener could not open it, and a restart would not free it.
package splice.app.cli.share

import splice.app.cli.peer.PeerExit
import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.share.Asked
import splice.share.DOOR_SEGMENT
import splice.share.DOOR_STARTING
import splice.share.ShareAddresses
import splice.share.ShareDirectory
import splice.share.SharedDoorLaunch
import splice.upstream.codemode.ProcessDispatchers
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

internal const val SHARE_USAGE = "on [--port N] | off | status"

internal class ShareCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    env: EnvReader = EnvReader(System::getenv),
) {
    private val file = ShareTopologyFile(err, SHARE_VERB, env)
    private val restart = ShareRestart(out, err, env, SHARE_VERB)
    private val directory = ShareDirectory(ProcessDispatchers().io())

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
        if (!saved(wanted, current)) return PeerExit.REFUSED
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
            out.line(
                "sharing is off. ${CYAN}splice share on$RESET puts this machine's door on its LAN and tailnet, " +
                    "so your other machines' claude runs on its models.",
            )
            return PeerExit.DONE
        }
        val here = "http://127.0.0.1:${share.port}"
        return when (val read = directory.read(here)) {
            is Asked.Had -> door(share, read.value.machine, here)
            is Asked.Refused -> {
                err.line("$SHARE_VERB: sharing is on in ${file.path}, but nothing here answers on it: ${read.why}")
                err.line("  is splice running? ${CYAN}splice restart$RESET")
                PeerExit.REFUSED
            }
        }
    }

    /** The door of [machine], this machine's own listener at [here], said: open, still starting, or none. */
    private suspend fun door(share: ShareConfig, machine: String, here: String): Int =
        when (val door = directory.door(here)) {
            is Asked.Had -> offered(share, machine, door.value)
            is Asked.Refused ->
                if (DOOR_STARTING in door.why) offered(share, machine, null) else noDoor(machine, door.why)
        }

    /** A listener here that answers no door, said with [why] in the door read's own words. */
    private fun noDoor(machine: String, why: String): Int {
        err.line("$SHARE_VERB: $machine answers no door here: $why")
        return PeerExit.REFUSED
    }

    /** The [door] of [machine], which another machine on this LAN or tailnet runs `claude` on; null while it is still
     *  starting, which is said with nothing to run yet, since neither the add nor the variables would work. */
    private fun offered(share: ShareConfig, machine: String, door: SharedDoorLaunch?): Int {
        val reach = ShareAddresses(share, machine, LogSink { err.line(it.trimEnd()) }).reach()
        val server = reach.server ?: run {
            err.line("$SHARE_VERB: $machine has no address on a LAN, so no other machine can reach it")
            return PeerExit.REFUSED
        }
        out.line("sharing is on: $BOLD$server$RESET, on $machine's LAN and tailnet")
        if (door == null) {
            val again = "run ${CYAN}splice share status$RESET again in a moment"
            out.line("  ${"door".padEnd(COMMAND_PAD)} $server/$DOOR_SEGMENT  $DIM$DOOR_STARTING;$RESET $again")
            return PeerExit.DONE
        }
        doorLines(server, door)
        val others = reach.addresses - server
        if (others.isNotEmpty()) out.line("${DIM}by address: ${others.joinToString(", ")}$RESET")
        return PeerExit.DONE
    }

    /** The door at [server]: how many models it serves, the one add that puts another machine's `claude` on it, and the
     *  variables a Claude Code without splice, or Claude Desktop's Code tab, runs on it with. */
    private fun doorLines(server: String, door: SharedDoorLaunch) {
        val address = "$server/$DOOR_SEGMENT"
        out.line("  ${"door".padEnd(COMMAND_PAD)} $address  $DIM${door.served}$RESET")
        out.line("on another machine, its ${BOLD}claude$RESET runs on this door after:")
        out.line("  ${CYAN}splice remote add $server$RESET")
        out.line("a Claude Code without splice, or Claude Desktop's Code tab, runs on it with:")
        out.line("  CLAUDE_CODE_USE_MANTLE=1")
        out.line("  ANTHROPIC_BEDROCK_MANTLE_BASE_URL=$address")
        out.line("  AWS_BEARER_TOKEN_BEDROCK=$SHARE_NODE_TOKEN")
        out.line("  CLAUDE_CODE_MAX_CONTEXT_TOKENS=${door.window}")
    }

    /** [wanted] written to splice.toml, unless it shares on a port this machine can't open, said in the OS's words
     *  (held by another program, or below 1024 for a user on Linux). This machine's own share listener holds its port
     *  while sharing is on, and answers there. [current] is the read [wanted] was built from. */
    private suspend fun saved(wanted: Topology, current: Topology): Boolean {
        val port = wanted.share.port
        val refusal = if (wanted.share.enabled) bindRefusal(port) else null
        if (refusal != null && directory.read("http://127.0.0.1:$port") !is Asked.Had) {
            val another = "${CYAN}splice share on --port <N>$RESET"
            err.line("$SHARE_VERB: port $port can't be opened here ($refusal); choose another with $another")
            return false
        }
        return file.saved(wanted, current)
    }

    /** Why [port] can't be bound on every address, as the share listener binds it, in the OS's words; null when it can,
     *  or when it is no port at all, which the writer refuses in its own words. */
    private fun bindRefusal(port: Int): String? = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(port))
        }
        null
    } catch (refused: BindException) {
        refused.message ?: "the OS refused it"
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun usage(): Int {
        err.line("usage: splice share $SHARE_USAGE")
        return PeerExit.BAD_USAGE
    }
}

private const val SHARE_VERB = "splice share"
