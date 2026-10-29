// Goal (Oct 29, 2026, Marcos: "we already send a message saying the server is restarting and so the user knows about
// it, then we just have the new daemon take over", and yes to socket activation): a restart of splice through its
// systemd unit fails no request. systemd holds every port splice listens on, the control port and each head's, so a
// request that arrives while the daemon restarts waits in the kernel and the next daemon answers it: Claude Code
// prints no connection error and no session moves to its fallback model. `splice restart` lays one socket unit per
// listener from splice.toml, named for the listener, and starts it. Where a socket is missing, not running or on an
// old port, it stops the daemon first, since systemd cannot take a port the daemon holds, and the restart that follows
// starts the daemon on systemd's sockets. A head splice.toml no longer has loses its socket. Driven through
// ListenerSockets against the stand-in user manager, which refuses a splice socket while the daemon holds the ports.
package splice.lifecycle.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val CONTROL_SOCKET = "splice-control.socket"
private const val CLAUDEX_SOCKET = "splice-head-claudex.socket"
private const val KIMI_SOCKET = "splice-head-claude-kimi.socket"
private val LISTENERS = linkedMapOf("control" to 3096, "head-claudex" to 3099, "head-claude-kimi" to 3102)

class ListenerSocketsTest {
    @TempDir
    lateinit var root: Path

    private val lines = CopyOnWriteArrayList<String>()
    private val out = TerminalOutput { lines += it }
    private val manager by lazy { UserManagerStandIn(root) }

    private fun said(): String = lines.joinToString("\n")

    private fun units(): SpliceUnits = SpliceUnits(manager.data, manager.config, manager.record, out, manager.command)

    private fun sockets(): ListenerSockets = ListenerSockets(units(), out) { "0::/user.slice/app.slice/term.scope\n" }

    private fun laid(name: String): List<String> = Files.readAllLines(manager.data.resolve(name))

    /** The state a machine is in once a restart has handed the ports to systemd for [listeners]. */
    private fun handedOver(listeners: Map<String, Int>) {
        manager.activate(DAEMON_UNIT_NAME)
        sockets().prepare(DAEMON_UNIT_NAME, listeners)
        manager.activate(DAEMON_UNIT_NAME)
    }

    @Test
    fun `every listener gets a socket unit that holds its port under its name for splice's daemon`() {
        val templates = sockets().templates(LISTENERS)

        assertEquals(setOf(CONTROL_SOCKET, CLAUDEX_SOCKET, KIMI_SOCKET), templates.keys)
        for ((listener, port) in LISTENERS) {
            val unit = templates.getValue("splice-$listener.socket").decodeToString().lines()
            assertTrue(unit.first().startsWith("# splice-owned unit"), "the $listener socket is not marked as splice's")
            val wanted = listOf(
                "ListenStream=127.0.0.1:$port",
                "FileDescriptorName=$listener",
                "Service=$DAEMON_UNIT_NAME",
                "WantedBy=sockets.target",
            )
            assertEquals(emptyList<String>(), wanted.filterNot { it in unit }, "the $listener socket lacks these")
        }
    }

    @Test
    fun `the first restart under the unit stops the daemon so systemd takes every port, and the restart starts it`() {
        manager.activate(DAEMON_UNIT_NAME)

        sockets().prepare(DAEMON_UNIT_NAME, LISTENERS)

        val running = manager.running()
        assertEquals(setOf(CONTROL_SOCKET, CLAUDEX_SOCKET, KIMI_SOCKET), running, "systemd holds every port\n${said()}")
        assertFalse(DAEMON_UNIT_NAME in running, "the daemon still holds its ports, so no socket could take them")
        assertTrue("ListenStream=127.0.0.1:3099" in laid(CLAUDEX_SOCKET), "the socket is not laid in splice's folder")
    }

    @Test
    fun `a restart where systemd already holds every port leaves the daemon running, so the restart queues requests`() {
        handedOver(LISTENERS)

        sockets().prepare(DAEMON_UNIT_NAME, LISTENERS)

        assertEquals(
            setOf(CONTROL_SOCKET, CLAUDEX_SOCKET, KIMI_SOCKET, DAEMON_UNIT_NAME),
            manager.running(),
            "a restart over sockets systemd already holds must not stop the daemon first\n${said()}",
        )
    }

    @Test
    fun `under steady requests, a removed head loses its socket and a moved head is held on its new port`() {
        handedOver(LISTENERS)

        val moved = linkedMapOf("control" to 3096, "head-claudex" to 3199)
        sockets().prepare(DAEMON_UNIT_NAME, moved)

        assertEquals(
            setOf(CONTROL_SOCKET, CLAUDEX_SOCKET),
            manager.running(),
            "systemd holds the wrong sockets\n${said()}",
        )
        assertFalse(Files.exists(manager.data.resolve(KIMI_SOCKET)), "the removed head's socket is still laid")
        assertTrue("ListenStream=127.0.0.1:3199" in laid(CLAUDEX_SOCKET), "the moved head is not on its new port")
    }

    @Test
    fun `a restart through a unit other than splice's touches no socket`() {
        manager.activate("other-daemon.service")

        sockets().prepare("other-daemon.service", LISTENERS)

        assertEquals(setOf("other-daemon.service"), manager.running())
        assertFalse(Files.exists(manager.data.resolve(CONTROL_SOCKET)), "a socket was laid for another unit's daemon")
    }

    @Test
    fun `a head whose name a unit name cannot carry gets no socket and binds its own port`() {
        val templates = sockets().templates(LISTENERS + ("head-two words" to 3120))

        assertEquals(setOf(CONTROL_SOCKET, CLAUDEX_SOCKET, KIMI_SOCKET), templates.keys)
    }

    @Test
    fun `systemd reads every socket splice lays beside the daemon's unit`() {
        val daemon = requireNotNull(DaemonUnit(Path.of("/opt/jdk"), Path.of("/home/someone/.local/bin/splice")).bytes())
        units().lay(sockets().templates(LISTENERS) + (DAEMON_UNIT_NAME to daemon))

        val complaints = systemdSays(listOf(DAEMON_UNIT_NAME, CONTROL_SOCKET, CLAUDEX_SOCKET, KIMI_SOCKET))
        assumeTrue(complaints != null, "systemd-analyze is not installed here")
        assertEquals(emptyList<String>(), complaints, "systemd could not read splice's sockets as laid")
    }

    private fun systemdSays(names: Collection<String>): List<String>? {
        val run = Files.createDirectories(root.resolve("run"))
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"))
        val builder = ProcessBuilder(listOf("systemd-analyze", "--user", "verify") + names).redirectErrorStream(true)
        builder.environment().putAll(
            mapOf(
                "HOME" to Files.createDirectories(root.resolve("home")).toString(),
                "XDG_CONFIG_HOME" to root.resolve("config").toString(),
                "XDG_DATA_HOME" to root.resolve("data").toString(),
                "XDG_RUNTIME_DIR" to run.toString(),
            ),
        )
        val process = runCatching { builder.start() }.getOrNull() ?: return null
        val answer = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(30, TimeUnit.SECONDS)
        return answer.lines().filter { line -> names.any { it in line } }
    }
}
