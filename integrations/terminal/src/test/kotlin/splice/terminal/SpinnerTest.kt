// NEW: Spinner TTY frames and non-TTY plain line (cli-wizard CW-2).
package splice.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.GREEN
import java.util.concurrent.atomic.AtomicInteger

class SpinnerTest {

    @Test
    fun `non-TTY stop emits exactly one plain line and no escape codes`() {
        val buf = StringBuilder()
        val spinner = Spinner(out = buf, tty = false)
        spinner.start("working")
        spinner.update("still working")
        spinner.stop("done")
        assertEquals("done\n", buf.toString())
        assertFalse(buf.contains("\u001B"), buf.toString())
        assertEquals(1, buf.count { it == '\n' })
    }

    @Test
    fun `TTY start redraws in place and stop leaves one completed line`() {
        val scheduled = AtomicInteger(0)
        val idle = PulseScheduler {
            scheduled.incrementAndGet()
            AutoCloseable { }
        }
        val buf = StringBuilder()
        val spinner = Spinner(out = buf, tty = true, scheduler = idle)
        spinner.start("working")
        spinner.pulse()
        spinner.stop("done")
        val text = buf.toString()
        assertTrue(text.contains("\r\u001B[2K"), text)
        assertTrue(text.contains("⠋") || text.contains("⠙"), text)
        assertTrue(text.contains("$GREEN✓"), text)
        assertTrue(text.endsWith("done\n"), text)
        assertEquals(1, text.count { it == '\n' })
        assertEquals(1, scheduled.get())
    }
}
