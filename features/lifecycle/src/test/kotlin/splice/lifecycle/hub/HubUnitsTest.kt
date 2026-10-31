// Setup's MCP-sharing answer turns the hub on, and `splice upgrade` reloads a running front, only over splice's own
// units: the ones systemd loads from splice's folder as splice laid them, with no drop-in. Since Oct 25, 2026 splice
// lays its units in $XDG_DATA_HOME/systemd/user, and a unit of the same name in ~/.config/systemd/user is someone's
// override, which setup and upgrade leave to its owner. Driven through HubUnits against the stand-in user manager.
package splice.lifecycle.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.lifecycle.units.SpliceUnits
import splice.lifecycle.units.UserManagerStandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val SOCKET = "splice-mcp-front.socket"
private const val FRONT = "splice-mcp-front.service"

class HubUnitsTest {
    @TempDir
    lateinit var root: Path

    private val lines = CopyOnWriteArrayList<String>()
    private val out = TerminalOutput { lines += it }
    private val manager by lazy { UserManagerStandIn(root) }

    private fun said(): String = lines.joinToString("\n")

    private fun hub(): HubUnits =
        HubUnits(SpliceUnits(manager.data, manager.config, manager.record, out, manager.command), out)

    /** The hub's units as `splice install --all` lays them. */
    private fun installed(): HubUnits = hub().also { hub ->
        SpliceUnits(manager.data, manager.config, manager.record, out, manager.command).lay(hub.templates())
        lines.clear()
    }

    private fun override(name: String, text: String) {
        Files.createDirectories(manager.config)
        Files.writeString(manager.config.resolve(name), text)
    }

    @Test
    fun `setup turns the hub on over the units splice laid`() {
        installed().enable()

        assertTrue("enable --now $SOCKET" in manager.heard(), "the socket was not enabled: ${manager.heard()}")
        assertTrue("turned the MCP hub on" in said(), said())
    }

    @Test
    fun `setup leaves the hub off over a unit someone placed in the overrides folder, and prints the command`() {
        val hub = installed()
        override(SOCKET, "[Socket]\nListenStream=127.0.0.1:4095\n")

        hub.enable()

        assertFalse(manager.heard().any { it.startsWith("enable") }, "someone's socket was enabled: ${manager.heard()}")
        assertTrue("systemctl --user enable --now $SOCKET" in said(), said())
    }

    @Test
    fun `setup leaves the hub off when a drop-in changes splice's unit`() {
        val hub = installed()
        val dropIns = Files.createDirectories(manager.config.resolve("$FRONT.d"))
        Files.writeString(dropIns.resolve("override.conf"), "[Service]\nMemoryMax=1G\n")

        hub.enable()

        assertFalse(manager.heard().any { it.startsWith("enable") }, "a changed unit was enabled: ${manager.heard()}")
    }

    @Test
    fun `with no hub unit anywhere setup says nothing and turns nothing on`() {
        hub().enable()

        assertEquals(emptyList<String>(), lines.toList(), "setup spoke about units that are not there")
        assertEquals(emptyList<String>(), manager.heard(), "setup acted with no units")
    }

    @Test
    fun `an upgrade reloads a running front through the unit splice laid`() {
        val hub = installed()
        manager.activate(FRONT)

        hub.reloadFront()

        assertTrue("reload $FRONT" in manager.heard(), "the front was not reloaded: ${manager.heard()}")
        assertTrue("reloaded $FRONT" in said(), said())
    }

    @Test
    fun `over someone else's front unit an upgrade only prints the reload for its owner`() {
        val hub = installed()
        manager.activate(FRONT)
        override(FRONT, "[Service]\nExecStart=/usr/bin/their-front\n")

        hub.reloadFront()

        assertFalse(manager.heard().any { it.startsWith("reload") }, "their front was reloaded: ${manager.heard()}")
        assertTrue("systemctl --user reload $FRONT" in said(), said())
    }
}
