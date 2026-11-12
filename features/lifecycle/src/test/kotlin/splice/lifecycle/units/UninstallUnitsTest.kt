// Goal (Oct 29, 2026, splice-lead; Marcos: "fix everything"): `splice uninstall --all` takes away the systemd user
// units splice laid, so nothing of splice's runs or comes back after it. Before, it removed only the command links: the
// daemon's unit stayed enabled, so splice.service started again at every login from the jar left on disk, and since
// socket activation, systemd kept holding splice's ports and started the daemon again on the next request. Each unit
// splice laid, the daemon's, the MCP hub's and every listener socket, is stopped, disabled and its file removed from
// splice's folder, sockets before the daemon so no request starts it again. Splice's files carry its marker line,
// so a file in that folder without it is another program's and stays, and a unit in ~/.config/systemd/user, which
// overrides splice's, is its owner's and stays too. Driven through UninstallUnits against the stand-in user manager.
package splice.lifecycle.units

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.lifecycle.hub.HubUnits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val OTHER_SERVICE = "notes-sync.service"
private const val LOOKALIKE = "splice-review.service"
private val PORTS = linkedMapOf("control" to 3096, "head-claudex" to 3099)

class UninstallUnitsTest {
    @TempDir
    lateinit var root: Path

    private val lines = CopyOnWriteArrayList<String>()
    private val out = TerminalOutput { lines += it }
    private val manager by lazy { UserManagerStandIn(root) }

    private fun said(): String = lines.joinToString("\n")

    private fun units(): SpliceUnits = SpliceUnits(manager.data, manager.config, manager.record, out, manager.command)

    private fun sockets(): ListenerSockets = ListenerSockets(units(), out) { "0::/user.slice/app.slice/term.scope\n" }

    private fun splicesUnits(): Set<String> =
        HubUnits(units(), out).templates().keys + DAEMON_UNIT_NAME + sockets().templates(PORTS).keys

    /** A machine after `splice install --all` and a restart: the daemon's and the hub's units laid and enabled, and
     *  every listener socket laid, enabled and holding its port for the running daemon. */
    private fun installedAndRestarted() {
        val daemon = requireNotNull(DaemonUnit(Path.of("/opt/jdk"), Path.of("/home/someone/.local/bin/splice")).bytes())
        units().lay(HubUnits(units(), out).templates() + (DAEMON_UNIT_NAME to daemon))
        units().systemctl(listOf("enable", DAEMON_UNIT_NAME, "splice-mcp-front.socket"))
        manager.activate(DAEMON_UNIT_NAME)
        manager.activate("splice-mcp-front.socket")
        sockets().prepare(DAEMON_UNIT_NAME, PORTS)
        manager.activate(DAEMON_UNIT_NAME)
    }

    @Test
    fun `uninstall stops, disables and removes every unit splice laid, and the daemon does not come back`() {
        installedAndRestarted()
        assertTrue(manager.running().containsAll(sockets().templates(PORTS).keys), "the sockets hold the ports first")

        UninstallUnits(units(), out).retire()

        val ours = splicesUnits()
        assertEquals(emptySet<String>(), manager.running() intersect ours, "still running\n${said()}")
        assertEquals(emptySet<String>(), manager.enabled() intersect ours, "still enabled\n${said()}")
        assertEquals(emptyList<String>(), ours.filter { Files.exists(manager.data.resolve(it)) }, "still laid")
        assertTrue(said().contains(DAEMON_UNIT_NAME), "the uninstall does not say the daemon's unit went\n${said()}")
    }

    @Test
    fun `a unit without splice's marker in its folder, and an override in the user's folder, stay as they are`() {
        installedAndRestarted()
        val other = "[Unit]\nDescription=someone else's sync\n".encodeToByteArray()
        val lookalike = "[Unit]\nDescription=a review run someone wrote\n".encodeToByteArray()
        Files.write(manager.data.resolve(OTHER_SERVICE), other)
        Files.write(manager.data.resolve(LOOKALIKE), lookalike)
        Files.createDirectories(manager.config)
        val override = "[Unit]\nDescription=the user's own splice.service\n".encodeToByteArray()
        Files.write(manager.config.resolve(DAEMON_UNIT_NAME), override)
        manager.activate(OTHER_SERVICE)

        UninstallUnits(units(), out).retire()

        assertArrayEquals(other, Files.readAllBytes(manager.data.resolve(OTHER_SERVICE)))
        assertArrayEquals(lookalike, Files.readAllBytes(manager.data.resolve(LOOKALIKE)))
        assertArrayEquals(override, Files.readAllBytes(manager.config.resolve(DAEMON_UNIT_NAME)))
        assertTrue(OTHER_SERVICE in manager.running(), "another program's unit was stopped\n${said()}")
        assertFalse(Files.exists(manager.data.resolve(DAEMON_UNIT_NAME)), "splice's own daemon unit stayed")
        assertTrue(
            said().contains(manager.config.resolve(DAEMON_UNIT_NAME).toString()),
            "the uninstall does not name the override it leaves to its owner\n${said()}",
        )
    }

    @Test
    fun `a machine where splice laid no unit is left as it is, and nothing is said`() {
        Files.createDirectories(manager.data)
        val other = "[Unit]\nDescription=someone else's sync\n".encodeToByteArray()
        Files.write(manager.data.resolve(OTHER_SERVICE), other)

        UninstallUnits(units(), out).retire()

        assertArrayEquals(other, Files.readAllBytes(manager.data.resolve(OTHER_SERVICE)))
        assertEquals("", said())
    }
}
