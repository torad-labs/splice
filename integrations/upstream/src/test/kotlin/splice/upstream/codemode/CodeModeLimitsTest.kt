package splice.upstream.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeModeLimitsTest {
    private val marker = Regex(" \\[truncated (\\d+) chars]$")

    @Test
    fun `text within both limits is returned untouched`() {
        val boundary = "é".repeat(CodeModeLimits.MAX_TEXT_BYTES / 2)
        assertSame(boundary, CodeModeLimits.boundedText(boundary))
        assertSame(boundary, CodeModeLimits.boundedText(boundary, maxChars = boundary.length))
    }

    @Test
    fun `a 70 KiB result is cut under the byte limit and says how much is gone`() {
        val value = "é".repeat(35_840)
        val bounded = CodeModeLimits.boundedText(value)
        val gone = marker.find(bounded)?.groupValues?.get(1)?.toInt() ?: error("no marker in ${bounded.takeLast(40)}")
        val kept = bounded.substring(0, bounded.length - marker.find(bounded)!!.value.length)
        assertTrue(bounded.encodeToByteArray().size <= CodeModeLimits.MAX_TEXT_BYTES)
        assertTrue(value.startsWith(kept))
        assertEquals(value.length, kept.length + gone)
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        val bounded = CodeModeLimits.boundedText("😀".repeat(20_000))
        val kept = bounded.substringBefore(" [truncated ")
        assertTrue(kept.last().isLowSurrogate())
        assertEquals(0, kept.length % 2)
        assertTrue(bounded.encodeToByteArray().size <= CodeModeLimits.MAX_TEXT_BYTES)
    }

    @Test
    fun `a character cap below the byte limit bounds by characters`() {
        val bounded = CodeModeLimits.boundedText("a".repeat(500), maxChars = 100)
        assertTrue(bounded.length <= 100, bounded.length.toString())
        assertTrue(bounded.endsWith(" chars]"))
    }

    @Test
    fun `every positive cap is honoured, down to caps too small for a marker`() {
        for (cap in listOf(1, 5, 19, 20, 21, 22, 23, 30, 60)) {
            val bounded = CodeModeLimits.boundedText("a".repeat(500), maxChars = cap)
            assertTrue(bounded.length <= cap, "cap $cap -> ${bounded.length}: $bounded")
            assertTrue(CodeModeLimits.fitsText(bounded))
        }
        assertTrue(CodeModeLimits.boundedText("a".repeat(500), maxChars = 60).endsWith(" chars]"))
    }

    @Test
    fun `a cap too small for a marker still cuts on a code point, never inside a surrogate pair`() {
        val emoji = "\uD83D\uDE00"
        assertEquals("", CodeModeLimits.boundedText(emoji + "x", maxChars = 1))
        assertEquals(emoji, CodeModeLimits.boundedText(emoji + "x", maxChars = 2))
        for (cap in 1..6) {
            val bounded = CodeModeLimits.boundedText(emoji.repeat(3), maxChars = cap)
            assertTrue(bounded.length <= cap, "cap $cap -> ${bounded.length}")
            assertTrue(bounded.isEmpty() || bounded.last().isLowSurrogate(), "cap $cap must end on a code point")
        }
    }

    @Test
    fun `the prefix is the longest that fits with the marker actually emitted`() {
        // 65,537 a's: the widest marker (5 digits) would leave 3 bytes unused; the real marker
        // carries 2 digits and the prefix fills the ceiling exactly.
        val bounded = CodeModeLimits.boundedText("a".repeat(CodeModeLimits.MAX_TEXT_BYTES + 1))
        assertEquals(CodeModeLimits.MAX_TEXT_BYTES, bounded.encodeToByteArray().size)
        assertTrue(bounded.endsWith(" [truncated 22 chars]"), bounded.takeLast(30))
        // A character cap right at a digit-width boundary of the marker.
        val capped = CodeModeLimits.boundedText("b".repeat(200), maxChars = 100)
        assertEquals(100, capped.length, capped.takeLast(30))
        assertTrue(capped.endsWith(" chars]"))
    }

    @Test
    fun `the digit cliff is crossed when one more kept character narrows the marker`() {
        // Bytes: 65,514 a's + " [truncated 999 chars]" (22) = 65,536 exactly; stopping at 65,513 + the
        // 23-char 1000 marker would also fit but be one character shorter (review 4).
        val bytes = CodeModeLimits.boundedText("a".repeat(CodeModeLimits.MAX_TEXT_BYTES + 977))
        assertEquals(CodeModeLimits.MAX_TEXT_BYTES, bytes.encodeToByteArray().size)
        assertTrue(bytes.endsWith(" [truncated 999 chars]"), bytes.takeLast(30))
        // Characters: 201 a's + the 22-char 999 marker = 223 exactly.
        val chars = CodeModeLimits.boundedText("a".repeat(1_200), maxChars = 223)
        assertEquals(223, chars.length, chars.takeLast(30))
        assertTrue(chars.endsWith(" [truncated 999 chars]"), chars.takeLast(30))
    }
}
