// NEW: WizardFrame badge, outro, cancel-as-single-exit, confirm delegates (cli-wizard CW-5).
package splice.app.cli.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.BG_CYAN
import splice.core.terminal.BLACK
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET

class WizardFrameTest {

    @Test
    fun `intro is a cyan-on-black padded badge`() {
        val buf = StringBuilder()
        WizardFrame(buf).intro("splice setup")
        assertEquals("$BG_CYAN$BLACK splice setup $RESET\n", buf.toString())
    }

    @Test
    fun `outro is one green line`() {
        val buf = StringBuilder()
        WizardFrame(buf).outro("Toolkit ready!")
        assertEquals("$GREEN" + "Toolkit ready!$RESET\n", buf.toString())
    }

    @Test
    fun `cancel prints one dim line and is the only member that raises cancellation`() {
        val buf = StringBuilder()
        val frame = WizardFrame(buf, ask = ConfirmPrompt { _, d -> d })
        frame.intro("t")
        frame.step("s")
        frame.note("n", listOf("x"))
        frame.confirm("q", true)
        frame.outro("o")
        val thrown = assertThrows(WizardCancelled::class.java) { frame.cancel("stopped") }
        assertEquals("stopped", thrown.message)
        assertEquals("$DIM" + "stopped$RESET\n", buf.toString().lines().last { it.isNotEmpty() } + "\n")
    }

    @Test
    fun `confirm with no ask argument returns the TTY-less AdminSupport default`() {
        val frame = WizardFrame(StringBuilder())
        assertTrue(frame.confirm("Install now?", true))
        assertFalse(frame.confirm("Install now?", false))
    }
}
