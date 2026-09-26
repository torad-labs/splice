// NEW: CW-3 — SelectPrompt through scripted keys and an injected Appendable.
package splice.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class SelectPromptTest {

    @Test
    fun `Enter chooses the initial option`() {
        val buf = StringBuilder()
        val outcome = prompt(buf, keys = byteArrayOf(13), tty = true).ask(
            "pick",
            OPTIONS,
            initialIndex = 0,
        )
        assertEquals(SelectOutcome.Chosen("a"), outcome)
    }

    @Test
    fun `Down wraparound at the last row selects the first`() {
        val buf = StringBuilder()
        val outcome = prompt(buf, keys = byteArrayOf(27, 91, 66, 13), tty = true).ask(
            "pick",
            OPTIONS,
            initialIndex = 2,
        )
        assertEquals(SelectOutcome.Chosen("a"), outcome)
    }

    @Test
    fun `Up wraparound at the first row selects the last`() {
        val buf = StringBuilder()
        val outcome = prompt(buf, keys = byteArrayOf(27, 91, 65, 13), tty = true).ask(
            "pick",
            OPTIONS,
            initialIndex = 0,
        )
        assertEquals(SelectOutcome.Chosen("c"), outcome)
    }

    @Test
    fun `Escape yields Cancelled`() {
        val buf = StringBuilder()
        val outcome = prompt(buf, keys = byteArrayOf(27), tty = true).ask(
            "pick",
            OPTIONS,
            initialIndex = 0,
        )
        assertEquals(SelectOutcome.Cancelled, outcome)
    }

    @Test
    fun `a Down repaint rewinds exactly the lines it drew`() {
        val buf = StringBuilder()
        prompt(buf, keys = byteArrayOf(27, 91, 66, 13), tty = true).ask(
            "pick",
            OPTIONS,
            initialIndex = 0,
        )
        val lines = 1 + OPTIONS.size
        val ups = CSI_A_COUNT.findAll(buf).count()
        assertEquals(lines, ups, "one repaint must rewind one cursor-up per drawn line")
        val erases = CSI_EL_COUNT.findAll(buf).count()
        assertEquals(lines, erases)
    }

    @Test
    fun `non-TTY returns the default and emits no escape codes`() {
        val buf = StringBuilder()
        val outcome = prompt(buf, keys = byteArrayOf(), tty = false).ask(
            "pick",
            OPTIONS,
            initialIndex = 1,
        )
        assertEquals(SelectOutcome.Chosen("b"), outcome)
        assertFalse('\u001B' in buf, "non-TTY must emit no escape character")
        assertEquals("", buf.toString())
    }

    @Test
    fun `non-TTY out-of-range index coerces rather than throwing`() {
        val buf = StringBuilder()
        val high = prompt(buf, keys = byteArrayOf(), tty = false).ask(
            "pick",
            OPTIONS,
            initialIndex = 99,
        )
        assertEquals(SelectOutcome.Chosen("c"), high)
        val low = prompt(buf, keys = byteArrayOf(), tty = false).ask(
            "pick",
            OPTIONS,
            initialIndex = -1,
        )
        assertEquals(SelectOutcome.Chosen("a"), low)
    }

    private fun prompt(buf: StringBuilder, keys: ByteArray, tty: Boolean): SelectPrompt {
        val stty = object : SttyCommand {
            override fun run(args: List<String>): SttyResult =
                if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        }
        return SelectPrompt(
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
    }
}

private val OPTIONS = listOf(
    SelectOption("a", "Alpha", "1"),
    SelectOption("b", "Beta", "2"),
    SelectOption("c", "Gamma", "3"),
)

private val CSI_A_COUNT = Regex("\\u001B\\[A")
private val CSI_EL_COUNT = Regex("\\u001B\\[2K")
