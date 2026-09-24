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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

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

    // 2026-09-24: the dashboard opens unlocked. The key reaches the browser through a redirect page
    // the operator alone can read, in the address's fragment (which never reaches the daemon), so no
    // HTTP answer and no argv ever carries it.
    @Test
    fun `the launch page hands the key over in the fragment, and only its owner can read it`() {
        val dir = Files.createTempDirectory("dashboard-launch")

        val page = DashboardCommand(ConsolePresence { false }).launchPage(dir, "http://127.0.0.1:3096", KEY)

        assertEquals(dir.resolve(LAUNCH_PAGE), page)
        val html = Files.readString(page)
        assertTrue(html.contains("content=\"0;url=http://127.0.0.1:3096/#k=$KEY\""), html)
        assertTrue(html.contains("<a href=\"http://127.0.0.1:3096/#k=$KEY\">"), html)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(page))
    }
}
