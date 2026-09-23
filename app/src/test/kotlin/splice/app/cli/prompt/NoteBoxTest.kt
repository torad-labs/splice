// NEW: NoteBox display-width border alignment (cli-wizard CW-2).
package splice.app.cli.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.CYAN
import splice.core.terminal.RESET

class NoteBoxTest {

    @Test
    fun `a cyan content line borders identically to the same line uncoloured`() {
        val plain = capture { NoteBox(it).render("note", listOf("hello world")) }
        val coloured = capture { NoteBox(it).render("note", listOf("$CYAN" + "hello world$RESET")) }
        assertEquals(visible(plain), visible(coloured))
        assertTrue('\u001B' in coloured, "the coloured render must actually carry SGR")
        assertEquals(plain.lines().size, coloured.lines().size)
        plain.lines().zip(coloured.lines()).forEach { (a, b) ->
            assertEquals(visible(a).length, visible(b).length, "ragged border on: $b")
        }
    }

    @Test
    fun `the title sits in the top rule`() {
        val rendered = visible(capture { NoteBox(it).render("heads", listOf("claudex")) })
        val rows = rendered.lines().filter { it.isNotEmpty() }
        assertTrue(rows.first().contains("─ heads "), rows.first())
        assertTrue(rows.last().startsWith("╰"), rows.last())
    }

    private fun capture(block: (Appendable) -> Unit): String {
        val buf = StringBuilder()
        block(buf)
        return buf.toString()
    }

    private fun visible(text: String): String = sgr.replace(text, "")

    private val sgr = Regex("\u001B\\[[0-9;]*m")
}
