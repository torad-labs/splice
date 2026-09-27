// NEW: V4-338 — a file's lines read from its end are the lines DayFiles reads forward, in reverse, on the
// same bytes: the same splits (\n, \r, \r\n, no empty line after a final terminator) and the same lenient
// decode, across the 64 KiB windows the backward scan reads in. V4-343: a line's bytes are its raw bytes.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// why: BackwardLines' scan window, so a case can put a split terminator or character across its edge
private const val WINDOW = 64 * 1024

// DR-186's backstop (JsonlSinkTest's idiom): a read that never reaches its end wedges the suite rather
// than failing, so each case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BackwardLinesTest {

    private fun backward(file: Path): List<String> =
        mutableListOf<String>().also { lines ->
            val read = BackwardLines().read(file) { line ->
                lines += line.text()
                true
            }
            assertTrue(read, "a visit that never stops read to the file's start")
        }

    /** [bytes] as a day file: its lines read backward, and DayFiles' forward read of it. */
    private fun bothWays(dir: Path, bytes: ByteArray): Pair<List<String>, List<String>> {
        val day = dir.resolve("probe-2026-09-18.jsonl")
        Files.write(day, bytes)
        return backward(day) to DayFiles(dir, "probe").lines().toList()
    }

    private fun assertMirrors(dir: Path, bytes: ByteArray, case: String) {
        val (backward, forward) = bothWays(dir, bytes)
        assertEquals(forward.reversed(), backward, case)
    }

    @Test
    fun `terminators split as the forward read splits them`(@TempDir dir: Path) {
        mapOf(
            "a final newline" to "a\nb\nc\n",
            "no trailing newline" to "a\nb\nc",
            "CRLF" to "a\r\nb\r\n",
            "lone CR" to "a\rb\r",
            "empty lines" to "\n\na\n\n",
            "a lone newline" to "\n",
            "an empty file" to "",
        ).forEach { (case, text) -> assertMirrors(dir, text.toByteArray(), case) }
    }

    @Test
    fun `a CRLF split across the scan window is one terminator`(@TempDir dir: Path) {
        // From the end, the first window starts exactly at the \n, so its \r is read by the next one.
        val text = "a".repeat(70_000) + "\r\n" + "b".repeat(WINDOW - 1)
        val (backward, forward) = bothWays(dir, text.toByteArray())

        assertEquals(forward.reversed(), backward)
        assertEquals(listOf(WINDOW - 1, 70_000), backward.map(String::length), "no empty line and no stray \\r")
    }

    @Test
    fun `a multibyte character across the scan window reads whole`(@TempDir dir: Path) {
        // The euro sign's three bytes straddle the edge of the window ending at the file's last byte.
        val tail = "c".repeat(WINDOW - 2)
        val text = "first\n" + "b".repeat(1_000) + "€" + tail + "\n"
        val (backward, forward) = bothWays(dir, text.toByteArray())

        assertEquals(forward.reversed(), backward)
        assertTrue(backward.first().contains("b€c"), "the character split in two")
    }

    @Test
    fun `a character a disk-full append cut short costs its own line, as it does forward`(@TempDir dir: Path) {
        val cut = byteArrayOf(0xE2.toByte(), 0x82.toByte())
        assertMirrors(dir, "{\"n\":1}\n{\"p\":\"".toByteArray() + cut + "\n{\"n\":2}\n".toByteArray(), "cut")
    }

    @Test
    fun `a line's bytes are its raw bytes, read unsigned, its terminator not among them`(@TempDir dir: Path) {
        // The middle line starts with a cut character and runs past the scan window, so its bytes come from two.
        val middle = byteArrayOf(0xE2.toByte(), 0x82.toByte()) + "b".repeat(WINDOW + 10).toByteArray()
        val file = dir.resolve("f")
        Files.write(file, "first\r\n".toByteArray() + middle + "\nlast".toByteArray())
        val whole = mutableListOf<List<Byte>>()
        val firsts = mutableListOf<Int>()

        assertTrue(
            BackwardLines().read(file) { line ->
                whole += line.bytes().readAllBytes().toList()
                firsts += line.bytes().read()
                true
            },
        )

        assertEquals(listOf("last".toByteArray(), middle, "first".toByteArray()).map { it.toList() }, whole)
        assertEquals(listOf('l'.code, 0xE2, 'f'.code), firsts, "a byte past 0x7F reads as 0-255, as InputStream.read")
    }

    @Test
    fun `a visit that answers false stops the read, and a missing file has no lines`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("f"), "1\n2\n3\n4\n")
        val seen = mutableListOf<String>()

        val finished = BackwardLines().read(file) { line ->
            seen += line.text()
            seen.size < 2
        }

        assertFalse(finished)
        assertEquals(listOf("4", "3"), seen)
        assertTrue(BackwardLines().read(dir.resolve("absent")) { error("a missing file has no line: $it") })
    }
}
