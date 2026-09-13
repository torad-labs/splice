import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.CodeModeLimits

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
}
