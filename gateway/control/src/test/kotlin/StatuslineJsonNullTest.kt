// NEW (review 2026-08-28, PR 99): the statusline's own JSON reader must not leak a JSON `null`
// through as the four-character string "null". JsonNull IS a JsonPrimitive whose content is the
// literal "null", so the unfiltered `(el as? JsonPrimitive)?.content?.takeIf { isNotEmpty() }` read
// survived the emptiness filter and broke both of the renderer's fallback chains — the exact class
// JsonScalars.kt was written for, reintroduced in :control as a second copy rather than a reuse.
// Both cases below are Claude Code shapes: an unnamed model and a workspace with no current_dir.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.StatuslineRenderer

class StatuslineJsonNullTest {

    private val renderer = StatuslineRenderer(label = "codex")

    private fun render(stdin: String): String = renderer.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0)

    @Test
    fun `a null display_name falls through to the model id instead of rendering the word null`() {
        val line = render("""{"model":{"display_name":null,"id":"gpt-5.6-luna"}}""")
        assertTrue(line.contains("gpt-5.6-luna"), "the id is the documented fallback: $line")
        assertFalse(line.contains("null"), "a JSON null must never reach the bar as text: $line")
    }

    @Test
    fun `a null workspace current_dir falls through to cwd instead of rendering the word null`() {
        val line = render("""{"workspace":{"current_dir":null},"cwd":"/home/someone/mythos-repo"}""")
        assertTrue(line.contains("mythos-repo"), "cwd is the documented fallback: $line")
        assertFalse(line.contains("null"), "a JSON null must never reach the bar as text: $line")
    }
}
