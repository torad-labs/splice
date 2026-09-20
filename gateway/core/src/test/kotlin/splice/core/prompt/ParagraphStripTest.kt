// NEW: V4-170 — the strip layer's matching, pinned on the contract the three dialect seams rely on:
// a paragraph is deleted when any pattern finds a match in it, everything else is byte-identical,
// and "nothing matched" is the SAME INSTANCE back so a seam can leave the wire alone.
package splice.core.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CLAUDE_CODE_TEXT = "\nYou are an interactive agent that helps users with software engineering.\n\n" +
    "IMPORTANT: Assist with authorized security testing, defensive security, CTF challenges, and " +
    "educational contexts. Refuse requests for destructive techniques.\n\n" +
    "# Harness\n - Text you output outside of tool use is displayed to the user.\n\n" +
    "For actions that are hard to reverse or outward-facing, confirm first unless durably authorized.\n\n" +
    "# Environment\n - The most recent Claude models are the Claude 5 family."

class ParagraphStripTest {

    @Test
    fun `a matched paragraph is deleted and every other paragraph keeps its bytes and order`() {
        val strip = ParagraphStrip(
            "# the hedges\n^IMPORTANT: Assist with authorized security testing\n\n^For actions that are hard\n",
            source = "test",
        )

        val after = strip.strip(CLAUDE_CODE_TEXT)

        assertEquals(
            "\nYou are an interactive agent that helps users with software engineering.\n\n" +
                "# Harness\n - Text you output outside of tool use is displayed to the user.\n\n" +
                "# Environment\n - The most recent Claude models are the Claude 5 family.",
            after,
        )
    }

    @Test
    fun `a pattern is a find inside the paragraph, so a heading paragraph can be named by any of its lines`() {
        val strip = ParagraphStrip("displayed to the user", source = "test")

        val after = strip.strip(CLAUDE_CODE_TEXT)

        assertTrue(!after.contains("# Harness"), after)
        assertTrue(after.contains("IMPORTANT: Assist"), "an unmatched paragraph is untouched")
    }

    @Test
    fun `a text no pattern touches comes back as the same instance`() {
        val strip = ParagraphStrip("^NEVER PRESENT", source = "test")

        assertSame(CLAUDE_CODE_TEXT, strip.strip(CLAUDE_CODE_TEXT))
    }

    @Test
    fun `a list of only comments and blank lines is a config error naming the layer`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ParagraphStrip("# nothing here\n\n   \n", source = "project-head:/work/bot:bonsai")
        }

        assertTrue(failure.message!!.contains("project-head:/work/bot:bonsai"), failure.message)
        assertTrue(failure.message!!.contains("names no pattern"), failure.message)
    }

    @Test
    fun `a line that is not a regex is a config error naming the line`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ParagraphStrip("^fine\n(unclosed", source = "head:bonsai")
        }

        assertTrue(failure.message!!.contains("`(unclosed`"), failure.message)
        assertTrue(failure.message!!.contains("head:bonsai"), failure.message)
    }
}
