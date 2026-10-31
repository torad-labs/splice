// NEW: log-tail-copies (2026-10-25) — the trailing window every tail reader shows (the console's log, `splice logs`,
// the doctor tail, the perf and compact HUDs, the peer journal, the daemon-log roll check), against a plain String
// model of its contract over seeded generated files: LF, CRLF, lone CR and blank lines, multibyte and malformed bytes,
// characters cut just before a terminator, a torn final line, and windows of one byte, the whole file, past the file,
// exactly at a line start and mid-character. The same windows then answer one head's lines and the newest few of
// them, as the model filtered by String.contains and cut with takeLast.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

// why: enough files that every piece meets every terminator and every window edge many times, in seconds
private const val FILES = 2_000

// why: the sweep replays: a red names its file and window, and the same seed rebuilds them
private const val SEED = 20_261_025

// why: lines per file, none to a couple of dozen, so windows land on every kind of boundary
private const val MAX_LINES = 24

// why: pieces per line, so a line can be empty, short, or carry several tags and characters
private const val MAX_PIECES = 6

// why: how far past the file's end the past-the-file window reaches
private const val PAST_END = 64

private const val LF = '\n'.code.toByte()
private const val CR = '\r'.code.toByte()

class JsonlTailWindowTest {
    private val utf8 = Charsets.UTF_8

    /** What a line is built from: ASCII text, JSON's escapes, NUL, two-, three- and four-byte characters, the head
     *  tags with [claudex] also inside another head's line, and bytes that are never valid UTF-8. */
    private val pieces: List<ByteArray> = listOf(
        "alpha", "beta 42", " ", "{\"k\":\"v\"}", "\"", "\\", "\t", "\u0000", "é", "…", "😀",
        "[claudex] ", "[claude-kimi] ", "[claudex-2] ", "[grok] ", "saw [claudex] too",
    ).map { it.toByteArray(utf8) } + listOf(
        bytes(0xFF),
        bytes(0x80),
        bytes(0xC0, 0xAF),
        bytes(0xED, 0xA0, 0x80),
    )

    /** A multibyte character missing its last bytes, written just before a line's terminator. */
    private val cuts: List<ByteArray> = listOf(
        bytes(0xC3),
        bytes(0xE2),
        bytes(0xE2, 0x80),
        bytes(0xE0, 0x80),
        bytes(0xF0),
        bytes(0xF0, 0x9F),
        bytes(0xF0, 0x9F, 0x98),
    )

    private val terminators: List<ByteArray> =
        listOf("\n", "\r\n", "\r", "\n\n", "\r\n\r\n", "\r\r\n").map { it.toByteArray(utf8) }

    /** Each head filter (none, an ASCII tag, a character beyond ASCII, the empty text) with each line count. */
    private val asks: List<Pair<String?, Int>> = listOf(null, "[claudex]", "…", "").flatMap { containing ->
        listOf(0, 1, 3, Int.MAX_VALUE).map { limit -> containing to limit }
    }

    @Test
    fun `every window of every generated file answers the string model's lines and complete end`(
        @TempDir dir: Path,
    ) = sweep(dir) { file, content, maxBytes ->
        val expected = model(content, maxBytes)
        assertEquals(expected, JsonlSink.readTailAt(file, maxBytes)) { shown(content, maxBytes) }
        assertEquals(expected.lines, JsonlSink.readTail(file, maxBytes)) { shown(content, maxBytes) }
    }

    @Test
    fun `one head's lines and the newest few of them come from the same window`(@TempDir dir: Path) =
        sweep(dir) { file, content, maxBytes ->
            val whole = model(content, maxBytes)
            asks.forEach { (containing, limit) ->
                val kept = whole.lines.filter { containing == null || containing in it }.takeLast(limit)
                val read = JsonlSink.readTailAt(file, maxBytes, containing, limit)
                assertEquals(TailAt(kept, whole.completeEnd), read) {
                    "containing $containing, limit $limit, ${shown(content, maxBytes)}"
                }
            }
        }

    /** Every generated file, each read through every window [windows] picks for it. */
    private fun sweep(dir: Path, check: (Path, ByteArray, Int) -> Unit) {
        val random = Random(SEED)
        val file = dir.resolve("tail.log")
        repeat(FILES) {
            val content = generatedFile(random)
            Files.write(file, content)
            windows(content, random).forEach { maxBytes -> check(file, content, maxBytes) }
        }
    }

    /**
     * The tail contract in plain String terms. Decode the trailing [maxBytes] window; when it starts inside the file,
     * drop through its first LF, even when that first line is whole; drop what follows its last LF, a torn tail; split
     * as lineSequence does, on CRLF, LF or a lone CR; drop empty lines. The complete end is the window start plus the
     * byte index just past its last LF, the window start when it holds none, and 0 for an empty file.
     */
    private fun model(content: ByteArray, maxBytes: Int): TailAt {
        if (content.isEmpty()) return TailAt(emptyList(), 0L)
        val readFrom = (content.size - maxBytes).coerceAtLeast(0)
        val window = content.copyOfRange(readFrom, content.size)
        val lastLf = window.lastIndexOf(LF)
        val completeEnd = readFrom.toLong() + (lastLf + 1)
        val text = String(window, utf8)
        val afterFirst = if (readFrom > 0) text.substringAfter('\n', "") else text
        val lines = afterFirst.substringBeforeLast('\n', "").lineSequence().filter { it.isNotEmpty() }.toList()
        return TailAt(lines, completeEnd)
    }

    /** Lines of pieces, a cut character before some terminators, and a third of the files ending in a torn line. */
    private fun generatedFile(random: Random): ByteArray {
        val out = ByteArrayOutputStream()
        val lines = random.nextInt(MAX_LINES + 1)
        repeat(lines) { line ->
            repeat(random.nextInt(MAX_PIECES + 1)) { out.write(pieces.random(random)) }
            if (random.nextInt(4) == 0) out.write(cuts.random(random))
            val torn = line == lines - 1 && random.nextInt(3) == 0
            if (!torn) out.write(terminators.random(random))
        }
        return out.toByteArray()
    }

    /** No window, one byte, the whole file, past the file, a random cut, exactly at a line start, and mid-character. */
    private fun windows(content: ByteArray, random: Random): List<Int> {
        val size = content.size
        val lineStarts = (1 until size).filter { content[it - 1] == LF || content[it - 1] == CR }
        val midCharacter = (1 until size).filter { (content[it].toInt() and 0xC0) == 0x80 }
        return buildList {
            add(0)
            add(1)
            add(size)
            add(size + 1 + random.nextInt(PAST_END))
            if (size > 0) add(1 + random.nextInt(size))
            lineStarts.randomOrNull(random)?.let { add(size - it) }
            midCharacter.randomOrNull(random)?.let { add(size - it) }
        }
    }

    private fun shown(content: ByteArray, maxBytes: Int): String =
        "window $maxBytes of ${content.size} bytes: " + content.joinToString(" ") { "%02x".format(it) }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
