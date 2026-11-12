// NEW: CW-4 — MultiSelectPrompt through scripted keys and an injected Appendable.
package splice.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class MultiSelectPromptTest {

    @Test
    fun `Space toggles a row on and off`() {
        val on = capture(keys = byteArrayOf(32, 13), initial = emptySet(), minimum = 0)
        assertEquals(MultiSelectOutcome.Chosen(listOf("a")), on.outcome)
        val off = capture(keys = byteArrayOf(32, 13), initial = setOf("a"), minimum = 0)
        assertEquals(MultiSelectOutcome.Chosen(emptyList<String>()), off.outcome)
    }

    @Test
    fun `Enter honours the initial selection`() {
        val run = capture(keys = byteArrayOf(13), initial = setOf("b"), minimum = 1)
        assertEquals(MultiSelectOutcome.Chosen(listOf("b")), run.outcome)
    }

    @Test
    fun `Enter under the minimum prints the reason and does not return`() {
        val run = capture(keys = byteArrayOf(13, 27), initial = emptySet(), minimum = 1)
        assertEquals(MultiSelectOutcome.Cancelled, run.outcome)
        assertTrue("select at least 1" in run.buf)
    }

    @Test
    fun `Escape yields Cancelled`() {
        val run = capture(keys = byteArrayOf(27), initial = setOf("a"), minimum = 0)
        assertEquals(MultiSelectOutcome.Cancelled, run.outcome)
    }

    @Test
    fun `non-TTY returns the initial set and emits no escape codes`() {
        val run = capture(keys = byteArrayOf(), initial = setOf("c"), minimum = 1, tty = false)
        assertEquals(MultiSelectOutcome.Chosen(listOf("c")), run.outcome)
        assertFalse('\u001B' in run.buf, "non-TTY must emit no escape character")
        assertEquals("", run.buf)
    }

    @Test
    fun `non-TTY below minimum returns Cancelled`() {
        val run = capture(keys = byteArrayOf(), initial = emptySet(), minimum = 1, tty = false)
        assertEquals(MultiSelectOutcome.Cancelled, run.outcome)
        assertEquals("", run.buf)
    }

    private data class Run(val outcome: MultiSelectOutcome<String>, val buf: String)

    private fun capture(
        keys: ByteArray,
        initial: Set<String>,
        minimum: Int,
        tty: Boolean = true,
    ): Run {
        val buf = StringBuilder()
        val stty = object : SttyCommand {
            override fun run(args: List<String>): SttyResult =
                if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        }
        val prompt = MultiSelectPrompt(
            keys = KeyReader(ByteArrayInputStream(keys)),
            terminal = TerminalMode(
                stty = stty,
                hasConsole = { tty },
                addHook = {},
                removeHook = {},
            ),
            out = buf,
            hasConsole = { tty },
        )
        val outcome = prompt.ask("pick", OPTIONS, initiallySelected = initial, minimum = minimum)
        return Run(outcome, buf.toString())
    }
}

private val OPTIONS = listOf(
    SelectOption("a", "Alpha", "1"),
    SelectOption("b", "Beta", "2"),
    SelectOption("c", "Gamma", "3"),
)
