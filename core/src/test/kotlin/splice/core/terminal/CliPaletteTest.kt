// NEW: the palette's contract, and one arm that is the whole reason the type exists.
//
// THE LOAD-BEARING ARM is `no colour emits no escape bytes`. The design claim these surfaces rest
// on is that state is never carried by colour ALONE — every state also has a glyph, so a terminal
// with colour stripped reads the same. That claim is only worth anything if NONE really emits
// nothing: a palette that quietly kept one tone would leave an operator with NO_COLOR set reading
// raw SGR sequences in a piped log, which is worse than colour they asked not to have.
//
// The tones-are-distinct arm exists for the opposite direction: a palette whose live and strain
// resolved to the same bytes would pass every rendering test in the tree while making the status
// table unreadable, because nothing else compares two tones to each other.
package splice.core.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.EnvReader

class CliPaletteTest {

    private fun env(vararg pairs: Pair<String, String>): EnvReader {
        val map = pairs.toMap()
        return EnvReader { name -> map[name] }
    }

    private fun depth(vararg pairs: Pair<String, String>): ColorDepth =
        ColorDepthProbe(env(*pairs)).depth()

    @Test
    fun `NO_COLOR wins at any value, including empty`() {
        // no-color.org: PRESENCE is the signal. Testing for "true" is the usual way to get it wrong,
        // so the empty string is the arm that matters — and it must beat a 256-colour TERM.
        assertEquals(ColorDepth.NONE, depth("NO_COLOR" to "", "TERM" to "xterm-256color"))
        assertEquals(ColorDepth.NONE, depth("NO_COLOR" to "0", "TERM" to "xterm-256color"))
    }

    @Test
    fun `a dumb or absent TERM gets no colour`() {
        assertEquals(ColorDepth.NONE, depth("TERM" to "dumb"))
        assertEquals(ColorDepth.NONE, depth())
    }

    @Test
    fun `256color in TERM, or any COLORTERM, reaches the extended tones`() {
        assertEquals(ColorDepth.EXTENDED, depth("TERM" to "xterm-256color"))
        assertEquals(ColorDepth.EXTENDED, depth("TERM" to "screen-256color"))
        assertEquals(ColorDepth.EXTENDED, depth("TERM" to "xterm", "COLORTERM" to "truecolor"))
    }

    @Test
    fun `a plain TERM falls back to the eight basic colours`() {
        assertEquals(ColorDepth.BASIC, depth("TERM" to "xterm"))
        // Blank COLORTERM is not a declaration of anything; it must not promote the depth.
        assertEquals(ColorDepth.BASIC, depth("TERM" to "xterm", "COLORTERM" to ""))
    }

    @Test
    fun `no colour emits no escape bytes`() {
        val plain = CliPalette(ColorDepth.NONE)
        for (tone in listOf(plain.signal, plain.live, plain.strain, plain.dead)) {
            assertTrue(tone.isEmpty(), "a colour tone survived NO_COLOR: ${tone.toByteArray().toList()}")
        }
        val painted = plain.paint(plain.live, "claudex")
        assertEquals("claudex", painted, "paint() must not wrap when the tone is empty")
        assertFalse(painted.contains(ESC), "an SGR sequence reached a NO_COLOR terminal")
    }

    @Test
    fun `NO_COLOR strips attributes too, so a row is exactly its plain text`() {
        // Bold and dim are attributes rather than colours, and NO_COLOR is written about colour, so
        // keeping them was defensible — the first cut of this class did. It was still the wrong
        // contract: a caller could then only assert "no COLOUR escapes", which means knowing which
        // SGR codes count, and a piped log still carried bytes the operator asked not to have.
        // "NONE renders exactly the plain text" is one assertion, and StatusTableTest leans on it.
        val plain = CliPalette(ColorDepth.NONE)
        assertTrue(plain.quiet.isEmpty(), "dim survived NO_COLOR")
        assertTrue(plain.strong.isEmpty(), "bold survived NO_COLOR")
        assertTrue(plain.off.isEmpty(), "a reset survived NO_COLOR, so something opened a run")
        // Attributes are still real at every depth that HAS colour — this is not their removal.
        assertEquals(BOLD, CliPalette(ColorDepth.BASIC).strong)
        assertEquals(DIM, CliPalette(ColorDepth.BASIC).quiet)
    }

    @Test
    fun `the four state tones are distinct at every depth that has colour`() {
        for (depth in listOf(ColorDepth.BASIC, ColorDepth.EXTENDED)) {
            val p = CliPalette(depth)
            val tones = listOf(p.signal, p.live, p.strain, p.dead)
            assertEquals(tones.size, tones.toSet().size, "two states share bytes at $depth: $tones")
        }
    }

    @Test
    fun `paint closes every tone it opens`() {
        val p = CliPalette(ColorDepth.EXTENDED)
        val painted = p.paint(p.strain, "sign in needed")
        assertTrue(painted.startsWith(p.strain), "tone must open the run")
        assertTrue(painted.endsWith(RESET), "an unclosed tone bleeds into the next column")
    }
}

/** The escape byte an SGR sequence opens with; the NO_COLOR arm asserts none reaches the terminal. */
private const val ESC = "\u001B"
