// NEW: which ports a restart's stop must see freed, and when that list cannot be trusted. A daemon that answers
// /api/heads with a list the CLI cannot read is running heads nobody can name, so the toml's ports never make
// that stop look complete.
package splice.lifecycle.restart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.Reading

class StopScopeTest {
    private val restart = RestartCommand(TerminalOutput {}, TerminalOutput {}, EnvReader { null }, RunningJar { null })

    @Test
    fun `a head list the daemon answered but the CLI cannot read keeps the stop degraded beside toml ports`() {
        val scope = restart.stopScope(Reading.Malformed, listOf(TOML_PORT))

        assertEquals(listOf(TOML_PORT), scope.ports)
        assertTrue(scope.degraded, "an unreadable list hides heads the toml cannot name")
        assertTrue(scope.unseen.orEmpty().contains("cannot read"), scope.unseen)
    }

    @Test
    fun `a refused head list leaves the toml ports to stand alone`() {
        val scope = restart.stopScope(Reading.Refused, listOf(TOML_PORT))

        assertEquals(listOf(TOML_PORT), scope.ports)
        assertNull(scope.unseen)
    }

    @Test
    fun `no ports from either source is degraded and says both failed`() {
        val scope = restart.stopScope(Reading.Answered(emptyList()), emptyList())

        assertTrue(scope.degraded)
        assertTrue(scope.unseen.orEmpty().contains("unreachable"), scope.unseen)
    }

    @Test
    fun `the daemon's own list and the toml are stopped together`() {
        val scope = restart.stopScope(Reading.Answered(listOf(LIVE_PORT, TOML_PORT)), listOf(TOML_PORT, OTHER_PORT))

        assertEquals(listOf(LIVE_PORT, TOML_PORT, OTHER_PORT), scope.ports)
        assertNull(scope.unseen)
    }

    private companion object {
        const val LIVE_PORT = 40_001
        const val TOML_PORT = 40_002
        const val OTHER_PORT = 40_003
    }
}
