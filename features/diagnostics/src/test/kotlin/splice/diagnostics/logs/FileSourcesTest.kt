package splice.diagnostics.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.random.Random

// why: enough generated log paths that line feeds land first, last and side by side in a name and beside a CR, in
// every log state, in well under a second
private const val PATHS = 240

// why: the sweep replays: a red names its case, and the same seed rebuilds it
private const val SEED = 20_261_025

class FileSourcesTest {
    /** What a generated directory name is made of: plain text, and line feeds alone, side by side and beside a CR. A
     *  name stays ASCII, so it encodes under whatever locale the test JVM runs with. */
    private val namePieces = listOf("a", "b c", ".", "\n", "\n\n", "\r", "\r\n", "\n\r")

    /** What a present log is made of: a head's lines, another head's, a blank line, a CRLF and a torn last line. */
    private val logPieces = listOf("[claudex] one\n", "[grok] two\n", "\n", "[claudex] three\r\n", "[claudex] torn")

    @Test
    fun `log source filters a shared bounded tail without reading the whole file`(@TempDir dir: Path) {
        val file = dir.resolve("splice-log-source.log")
        val rows = buildString {
            repeat(3_000) { index ->
                append(if (index % 2 == 0) "[codex]" else "[grok]")
                append(" row-")
                append(index)
                append('\n')
            }
        }
        Files.writeString(file, rows)

        val source = LogFileSource(file, "[codex]")
        val tail = source.tail(Int.MAX_VALUE).lines().filter { it.isNotEmpty() }

        assertEquals(1_500, tail.size)
        assertTrue(tail.last().contains("row-2998"))
        assertFalse(tail.any { "[grok]" in it })
        assertEquals(file.toString(), source.path())
    }

    @Test
    fun `a missing log says so and a non-positive tail is empty`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("missing.log")
        val source = LogFileSource(missing)
        val said = "[logs unavailable: $missing does not exist yet; the daemon creates it when it starts]"
        assertEquals(said, source.tail(10))
        assertEquals(listOf(said), source.tailLines(10))

        Files.writeString(missing, "[codex] one\n")
        assertEquals("", source.tail(0))
        assertEquals(emptyList<String>(), source.tailLines(0))
    }

    @Test
    fun `every log path and state answers tailLines as the routes cut tail's text, and tail's notice whole`(
        @TempDir dir: Path,
    ) {
        // The log routes sent tail's text cut at each line feed, empty pieces dropped. The notice names the log's path,
        // and a path may hold line feeds of its own, so the cut can make it several entries (log-tail-copies review,
        // Oct 25). tail keeps the notice whole, as splice logs prints it.
        val random = Random(SEED)
        repeat(PATHS) { case ->
            val name = "$case " + List(random.nextInt(1, 6)) { namePieces.random(random) }.joinToString("")
            val logs = dir.resolve(name).resolve("logs")
            val log = logs.resolve("daemon.log")
            val absent = "[logs unavailable: $log does not exist yet; the daemon creates it when it starts]"
            val unreadable = "[logs unavailable: $log unreadable ("
            if (case % 3 != 0) {
                Files.createDirectories(logs)
                Files.writeString(log, List(random.nextInt(0, 6)) { logPieces.random(random) }.joinToString(""))
            }
            when (case % 3) {
                0 -> assertRoutesCutTail(log) { it == absent }
                1 -> assertRoutesCutTail(log) { true }
                else -> shut(logs) { assertRoutesCutTail(log) { it.startsWith(unreadable) && it.endsWith(")]") } }
            }
        }
    }

    /** For every head filter and line count, tailLines is the routes' cut of tail's text, split at each line feed with
     *  the empty pieces dropped, and the text a positive count answers is one [answers] accepts. */
    private fun assertRoutesCutTail(log: Path, answers: (String) -> Boolean) {
        for (tag in listOf(null, "[claudex]")) {
            for (lines in listOf(0, 1, 3, Int.MAX_VALUE)) {
                val source = LogFileSource(log, tag)
                val text = source.tail(lines)
                val said = "$log, tag $tag, $lines lines: $text"
                assertEquals(text.split("\n").filter { it.isNotEmpty() }, source.tailLines(lines), said)
                assertTrue(lines <= 0 || answers(text), said)
            }
        }
    }

    /** Runs [block] with [dir] shut to everyone, so a read beneath it fails as unreadable, then opens it again. */
    private fun shut(dir: Path, block: () -> Unit) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        try {
            block()
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a head's tail is its own whole lines oldest first, and tail, tailLines and tailAt agree`(@TempDir dir: Path) {
        val file = dir.resolve("daemon.log")
        val torn = "[claudex] torn final line"
        Files.writeString(
            file,
            "[claudex] boot one\r\n" +
                "[grok] another head\n" +
                "[claudex] cr-left\r[claudex] cr-right\n" +
                "\n" +
                "[claudex] split\runtagged half\n" +
                "[claudex] é … 😀\n" +
                "[claude-kimi] mentions [claudex] here\n" +
                "[claudex-2] a longer tag, not this head\n" +
                torn,
        )
        val source = LogFileSource(file, "[claudex]")

        val own = "[claudex] boot one\n" +
            "[claudex] cr-left\n" +
            "[claudex] cr-right\n" +
            "[claudex] split\n" +
            "[claudex] é … 😀\n" +
            "[claude-kimi] mentions [claudex] here"
        assertEquals(own, source.tail(Int.MAX_VALUE))
        val lastTwo = "[claudex] é … 😀\n[claude-kimi] mentions [claudex] here"
        assertEquals(lastTwo, source.tail(2))
        assertEquals(listOf("[claudex] é … 😀", "[claude-kimi] mentions [claudex] here"), source.tailLines(2))
        val throughLastLf = Files.size(file) - torn.toByteArray(Charsets.UTF_8).size
        assertEquals(LogRebase(lastTwo, throughLastLf), source.tailAt(2))
    }

    @Test
    fun `the line cap counts only the head's own lines`(@TempDir dir: Path) {
        val file = dir.resolve("daemon.log")
        Files.writeString(file, (0 until 10_005).joinToString("") { "[claudex] row-$it\n[grok] row-$it\n" })

        val tail = LogFileSource(file, "[claudex]").tail(Int.MAX_VALUE)

        assertEquals((5 until 10_005).joinToString("\n") { "[claudex] row-$it" }, tail)
    }

    @Test
    fun `a window cut one byte into a tagged line drops it and keeps the head's lines after it`(@TempDir dir: Path) {
        val file = dir.resolve("daemon.log")
        val cut = "x[claudex] cut-me\n"
        val after = listOf("[claudex] first after the cut", "[claudex] second", "[claudex] last")
        val filler = "[grok] " + "f".repeat(1_000) + "\n"
        val fixed = cut.length + after.sumOf { it.length + 1 }
        // The window is the last LOG_TAIL_BYTES bytes, so a file one byte longer starts it at "[claudex] cut-me".
        val target = LOG_TAIL_BYTES + 1
        val fillers = (target - fixed) / filler.length - 1
        val pad = "[grok] " + "p".repeat(target - fixed - fillers * filler.length - "[grok] \n".length) + "\n"
        Files.writeString(
            file,
            cut + after[0] + "\n" + filler.repeat(fillers) + after[1] + "\n" + pad + after[2] + "\n",
        )
        assertEquals(target.toLong(), Files.size(file), "the window must start one byte into the tagged line")

        val tail = LogFileSource(file, "[claudex]").tail(Int.MAX_VALUE)

        assertEquals(after.joinToString("\n"), tail)
    }
}
