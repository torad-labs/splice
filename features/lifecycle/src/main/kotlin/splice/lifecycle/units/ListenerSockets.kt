// NEW (Oct 29, 2026, splice-lead; Marcos: "we already send a message saying the server is restarting and so the user
// knows about it, then we just have the new daemon take over", then yes to socket activation): systemd holds every
// port splice listens on, so a restart fails no request. Between the old daemon's exit and the new one's bind nothing
// listened, and Claude Code printed ECONNREFUSED; a request on a connection the stopping daemon closed printed
// ECONNRESET. With a socket unit per listener, a client that connects during a restart waits in the kernel's accept
// queue and the next daemon answers it (the daemon's side, adoption and the pause at drain start, is in
// integrations/http/listen). `splice restart` lays the sockets from splice.toml, one per listener, named for it, since
// the daemon matches a handed socket by its name. Host Shield was meant to hold these ports (Oct 10) and vendors no
// splice since Oct 25, so splice lays them itself, beside splice.service, in splice's units folder.
package splice.lifecycle.units

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path

/** What a restart through the daemon's unit does first about the sockets systemd holds for the daemon. */
public fun interface RestartSockets {
    /** [listeners] is each listener's name, as the daemon matches a handed socket, to its port. */
    public fun prepare(unit: String, listeners: Map<String, Int>)
}

/** The cgroup membership of the process running a restart, as /proc/self/cgroup reads. */
public fun interface OwnCgroup {
    public fun read(): String
}

/** The daemon's listening sockets as systemd units, laid through [units]. [cgroup] is read to keep a restart started
 *  inside splice.service from stopping the daemon it runs under. */
public class ListenerSockets(
    private val units: SpliceUnits,
    private val output: TerminalOutput,
    private val cgroup: OwnCgroup = OwnCgroup {
        Cancellables.runCatchingBestEffort { Files.readString(Path.of("/proc/self/cgroup")) }.getOrNull().orEmpty()
    },
) : RestartSockets {
    /** Unit name to splice's bytes for each listener's socket. A listener whose name a unit name cannot carry gets
     *  none, and binds its own port as it does without systemd. */
    public fun templates(listeners: Map<String, Int>): Map<String, ByteArray> {
        val template = javaClass.getResourceAsStream(TEMPLATE)?.use { it.readBytes().decodeToString() }
            ?: return emptyMap()
        val carried = listeners.filter { (name, port) -> CARRIED.matches(name) && port in PORTS }
        return carried.entries.associate { (name, port) ->
            val unit = SLOT.replace(template) { if (it.value == NAME_SLOT) name else port.toString() }
            unitName(name) to unit.encodeToByteArray()
        }
    }

    /** Before systemd restarts [unit]: when it is splice.service and systemd already holds every listener's port, as
     *  splice laid it, nothing is done, and the restart queues every request for the next daemon. Otherwise the daemon
     *  is stopped first, since systemd cannot take a port the daemon holds; the sockets of listeners splice.toml no
     *  longer has go, each socket is laid and started, and the restart that follows starts the daemon on them. */
    override fun prepare(unit: String, listeners: Map<String, Int>) {
        if (unit != DAEMON_UNIT_NAME) return
        val wanted = templates(listeners)
        val stale = units.laid().filter(::isListenerSocket) - wanted.keys.toSet()
        val laidAsWanted = stale.isEmpty() && wanted.all { (name, bytes) -> units.holds(name, bytes) }
        if (laidAsWanted && wanted.keys.all(::running)) return
        if (UNIT_CGROUP in cgroup.read()) {
            output.line(
                "splice: this restart runs inside $DAEMON_UNIT_NAME, so it leaves the ports to the daemon; " +
                    "run `splice restart` from a terminal to hand them to systemd",
            )
            return
        }
        handOver(wanted, stale)
    }

    /** Stops the daemon so systemd can take its ports, retires [stale], and lays and starts each socket in [wanted]. */
    private fun handOver(wanted: Map<String, ByteArray>, stale: List<String>) {
        output.line(
            "splice: handing splice's ports to systemd, so a restart's requests wait for the next daemon; " +
                "this restart stops the daemon first",
        )
        // The sockets systemd holds now go before the daemon does: a request on one of them while the daemon is down
        // would start it again on its own ports, and the sockets laid below could not take them.
        val held = (wanted.keys + stale).filter(::running)
        if (held.isNotEmpty()) units.systemctl(listOf("stop") + held)
        units.systemctl(listOf("stop", DAEMON_UNIT_NAME))
        if (stale.isNotEmpty()) {
            units.systemctl(listOf("disable") + stale)
            stale.forEach(units::remove)
            units.systemctl(listOf("daemon-reload"))
        }
        units.lay(wanted)
        units.systemctl(listOf("enable", "--now") + wanted.keys)
        val unheld = wanted.keys.filterNot(::running)
        if (unheld.isNotEmpty()) {
            output.line(
                "splice: systemd did not take ${unheld.joinToString(", ")}, so those listeners bind their own " +
                    "ports and a restart can still fail their requests. " +
                    "See: systemctl --user status ${unheld.first()}",
            )
        }
    }

    private fun running(name: String): Boolean = units.systemctl(listOf("is-active", "--quiet", name)) != null

    private fun unitName(listener: String): String = "$PREFIX$listener.socket"

    private fun isListenerSocket(name: String): Boolean = name == unitName(CONTROL) || HEAD_SOCKET.matches(name)
}

private const val TEMPLATE = "/splice/lifecycle/units/splice-listener.socket"
private const val PREFIX = "splice-"
private const val CONTROL = "control"
private const val NAME_SLOT = "@NAME@"
private val HEAD_SOCKET = Regex("splice-head-.+[.]socket")
private val SLOT = Regex("@NAME@|@PORT@")

// why: a unit name and a FileDescriptorName both carry these as written; anything else would be escaped or refused.
private val CARRIED = Regex("[A-Za-z0-9_.-]{1,200}")
private const val HIGHEST_PORT = 65_535
private val PORTS = 1..HIGHEST_PORT
private const val UNIT_CGROUP = "/$DAEMON_UNIT_NAME"
