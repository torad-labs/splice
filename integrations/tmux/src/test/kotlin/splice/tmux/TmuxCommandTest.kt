// WALLS for the one place splice runs a tmux client, against a REAL tmux server on this test's own socket
// (same reason as TmuxTerminalTest: the claim is about what tmux does, so a fake would only restate my belief).
package splice.tmux

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: how long a test waits for a real tmux to answer; a client answers in milliseconds, so ten seconds is a
// wedged call and not a slow one. The wait for the screen is a POLL against this deadline, never a sleep.
private const val DEADLINE_MS = 10_000L

// why: 100 rows of 999 cells is about 100 KB of screen, past the 64 KiB a pipe holds on Linux, so a reader that
// only starts after tmux exits cannot take it. Taller than the rows filled so the final marker has a row of its own.
private const val FILLED_ROWS = 100
private const val ROW_WIDTH = 999
private const val PANE_ROWS = 120

class TmuxCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val socket: Path get() = tmp.resolve("tmux.sock")

    @AfterEach
    fun teardown() {
        TmuxCommand("tmux", socket, DEADLINE_MS).run(listOf("kill-server"))
    }

    /** What the console shows of a session whose screen is bigger than a pipe: all of it, not a failed read. */
    @Test
    fun `a screen bigger than a pipe holds is read whole`() {
        val tmux = TmuxCommand("tmux", socket, DEADLINE_MS)
        val row = "x".repeat(ROW_WIDTH)
        tmux.must(
            listOf(
                "new-session", "-d", "-s", "wide", "-x", ROW_WIDTH.toString(), "-y", PANE_ROWS.toString(), "--",
                "sh", "-c", "yes $row | head -n $FILLED_ROWS; echo END; cat",
            ),
        )

        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        var screen = ""
        while (!screen.contains("END") && System.nanoTime() < until) {
            screen = tmux.must(listOf("capture-pane", "-p", "-t", "wide"))
        }

        assertTrue(screen.contains("END"), "the session's last line reached the screen that was read")
        assertEquals(FILLED_ROWS, screen.lines().count { it == row }, "every filled row came back whole")
    }
}
