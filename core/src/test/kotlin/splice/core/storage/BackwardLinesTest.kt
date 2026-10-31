// NEW: V4-338 — a file's lines read from its end are the lines DayFiles reads forward, in reverse, on the
// same bytes: the same splits (\n, \r, \r\n, no empty line after a final terminator) and the same lenient
// decode, across the 64 KiB windows the backward scan reads in. V4-343: a line's bytes are its raw bytes, and a
// file split at its settled end reads as the whole file does, before and after any append.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `lines of every short length split alike, so a terminator falls at every offset of a scanned word`(
        @TempDir dir: Path,
    ) {
        // V4-343: the scan steps eight bytes at a time, so each terminator must be found wherever it lands.
        val terminators = listOf("\n", "\r\n", "\r")
        val text = (0..40).joinToString("") { n -> "x".repeat(n) + terminators[n % terminators.size] }
        assertMirrors(dir, text.toByteArray(), "short lines")
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

    /** [text]'s lines as the forward read splits them. */
    private fun forward(text: String): List<String> = text.reader().readLines()

    /** [file]'s lines between [from] and [until], in the order they were written. */
    private fun LineFile.between(from: Long, until: Long): List<String> =
        mutableListOf<String>().also { lines ->
            lines(from, until) { line ->
                lines += line.text()
                true
            }
        }.asReversed()

    /** Every text of up to [length] characters drawn from [alphabet], the empty one first. */
    private fun texts(alphabet: String, length: Int): List<String> =
        (1..length).runningFold(listOf("")) { shorter, _ -> shorter.flatMap { t -> alphabet.map { t + it } } }
            .flatten()

    @Test
    fun `lines before the settled end are the whole file's there, and no append changes one`(@TempDir dir: Path) {
        // V4-343: a reader keeps what it counted before a file's settled end and reads only the rest later, so every
        // file of up to six bytes of a, \r and \n, and every append of up to two more, is split there both ways.
        val file = dir.resolve("f")
        texts("a\r\n", length = 6).forEach { text ->
            Files.writeString(file, text)
            val settled = checkNotNull(BackwardLines().open(file) { it.settledEnd() })
            texts("a\r\n", length = 2).forEach { appended ->
                val case = "${(text + "|" + appended).replace("\r", "\\r").replace("\n", "\\n")} settled at $settled"
                Files.writeString(file, text + appended)
                BackwardLines().open(file) { grown ->
                    val before = grown.between(0L, settled)
                    assertEquals(forward(text + appended), before + grown.between(settled, grown.size), case)
                    assertEquals(forward(text.take(settled.toInt())), before, case)
                }
            }
        }
    }

    @Test
    fun `a file's bytes from an offset are the ones it holds there, fewer at its end`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("f"), "abcdef")

        val read = BackwardLines().open(file) { f -> listOf(0L, 4L, 6L).map { String(f.bytes(it, 4)) } }

        assertEquals(listOf("abcd", "ef", ""), read)
        assertNull(BackwardLines().open<String>(dir.resolve("absent")) { error("a missing file is not opened") })
    }
}
