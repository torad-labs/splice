// NEW: v0.4.0 review — `splice dashboard` printed the management key to stdout wherever it ran. A model
// that runs it from a session's shell tool reads that output into its transcript, which is how the
// key retired on 2026-09-23 reached eight transcripts. The key is printed to a terminal and nowhere else.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.daemonclient.MgmtKeyRead
import splice.terminal.ConsolePresence

private const val KEY = "0123456789abcdef0123456789abcdef"

class DashboardKeyLineTest {

    @Test
    fun `an operator at a terminal is shown the key to paste`() {
        val line = DashboardCommand(ConsolePresence { true }).keyLine(MgmtKeyRead.Present(KEY))

        assertEquals("splice: dashboard key (paste if prompted): $KEY", line)
    }

    @Test
    fun `output that is not a terminal never carries the key, and says how to see it`() {
        val line = DashboardCommand(ConsolePresence { false }).keyLine(MgmtKeyRead.Present(KEY))

        assertFalse(line.orEmpty().contains(KEY), line)
        assertTrue(line.orEmpty().contains("in a terminal"), line)
    }

    @Test
    fun `an unreadable key is said either way, and an absent one is silent`() {
        val piped = DashboardCommand(ConsolePresence { false })

        assertTrue(piped.keyLine(MgmtKeyRead.Unreadable("permission denied")).orEmpty().contains("unreadable"))
        assertNull(piped.keyLine(MgmtKeyRead.Absent))
    }
}
