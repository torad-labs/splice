// NEW: Nov 22, 2026 — pins how Claude names a project's folder under its `<config>/projects/` tree, an outside
// contract read back by Claude Code and Desktop whenever they reopen history or memory beside it. Every expected
// name below is written once, by hand, from Desktop 6.28899.2's rule (its `sf`); a silent change strands Gemini
// transcripts where neither client can find them again.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClaudeProjectFolderTest {
    @Test
    fun `a short path keeps its letters and digits and loses every other character`() {
        assertEquals("-Home-Me2-work", ClaudeProjectFolder.of("/Home/Me2/work"))
    }

    @Test
    fun `two hundred characters exactly is kept whole, with no cut and no hash`() {
        // /tmp/ followed by 195 a's spells out 200 slugged characters: at the boundary Desktop stops keeping.
        val exactlyTwoHundred =
            "-tmp-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertEquals(exactlyTwoHundred, ClaudeProjectFolder.of("/tmp/" + "a".repeat(195)))
    }

    @Test
    fun `past two hundred the name is cut there and told apart by the whole path's base 36 hash`() {
        // /home/u/ followed by 260 b's spells out 268 slugged characters, so the name is the first 200 plus '-' and
        // abs(hash) in base 36 of the ORIGINAL path — separators still '/', never their slug. That suffix is eeo5tn.
        val pastTwoHundred =
            "-home-u-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                "-eeo5tn"
        assertEquals(pastTwoHundred, ClaudeProjectFolder.of("/home/u/" + "b".repeat(260)))
    }

    @Test
    fun `a path of only non-alphanumeric characters comes out all dashes`() {
        assertEquals("------", ClaudeProjectFolder.of("//__.."))
    }
}
