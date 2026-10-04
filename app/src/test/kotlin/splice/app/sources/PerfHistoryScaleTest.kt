// NEW: the large Usage window must not decode all earlier history on first load.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfKeys
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.READ

class PerfHistoryScaleTest {
    @Test
    fun `a broader window recovers cold skipped ranges in physical order`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        Files.writeString(file, (1..9).joinToString("") { """{"ts":$it,"outcome":"ok"}""" + "\n" })
        val source = PerfRowsFileSource(file)
        assertEquals((5L..9L).toList(), source.window(5).rows.map { it.ts })
        assertEquals((1L..9L).toList(), source.window(0).rows.map { it.ts })
    }

    @Test
    fun `parsed timestamps defeat duplicate and escaped cold skip hints`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        val lines = listOf(
            """{"ts":1}""",
            """{"ts":2,"ts":12}""",
            """{"ts":3,"ts":13}""",
            """{"ts":4,"ts":"14"}""",
            """{"ts":5,"detail":{"ts":15}}""",
        )
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        val source = PerfRowsFileSource(file)
        val read = source.window(10)
        assertEquals(listOf(12L, 13L), read.rows.map { it.ts })
        assertEquals(read, source.window(10))
        assertEquals(listOf(1L, 12L, 13L, 5L), source.window(0).rows.map { it.ts })
    }

    @Test
    fun `skipped counter anchors replay in physical order after clock steps`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        val stamps = listOf(1, 9, 2, 8, 3, 7, 4, 12, 5)
        val lines = stamps.mapIndexed { index, ts -> """{"ts":$ts,"async_io_drops":$index}""" }
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        val source = PerfRowsFileSource(file)
        val read = source.window(10)
        assertEquals(listOf(12L), read.rows.map { it.ts })
        assertEquals(6L, read.dropsBefore)
        assertEquals(read, source.window(10))
        assertEquals(stamps.map { it.toLong() }, source.window(0).rows.map { it.ts })
    }

    @Test
    fun `a skipped unterminated tail is not a retained complete range`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        Files.writeString(file, "{\"ts\":1}\n{\"ts\":2}")
        val source = PerfRowsFileSource(file)
        assertTrue(source.window(5).rows.isEmpty())
        Files.writeString(file, "\n{\"ts\":5}\n", APPEND)
        assertEquals(listOf(5L), source.window(5).rows.map { it.ts })
        assertEquals(listOf(1L, 2L, 5L), source.window(0).rows.map { it.ts })
    }

    @Test
    fun `bulk framing preserves buffer edge endings and split UTF8`(@TempDir dir: Path) {
        val first = "x".repeat(65_535)
        val second = "x".repeat(65_533) + "é"
        val final = "tail"
        listOf("\n", "\r", "\r\n").forEachIndexed { index, ending ->
            val file = dir.resolve("buffer-edge-$index")
            val bytes = (first + ending + second + ending + final).toByteArray(Charsets.UTF_8)
            Files.write(file, bytes)
            FileChannel.open(file, READ).use { channel ->
                val reader = PerfLineReader(channel, 0L, bytes.size.toLong())
                assertEquals(first, reader.next())
                assertTrue(reader.terminated)
                assertEquals(second, reader.next())
                assertTrue(reader.terminated)
                assertEquals(final, reader.next())
                assertEquals(false, reader.terminated)
                assertEquals(null, reader.next())
                assertEquals(bytes.size.toLong(), reader.position)
            }
        }
    }

    @Test
    fun `the reported week is complete while older history does not spend its decode budget`(@TempDir dir: Path) {
        val history = SyntheticPerfHistory(dir)
        history.create()
        val source = PerfRowsFileSource(history.file)
        val profiler = PerfHistoryProfile()
        val rows = profiler.source(source, dir.resolve("source.jfr"))
        val bytes = profiler.payload(rows)
        val tokens = rows.sumOf { (it.fields[PerfKeys.IN_TOKENS] ?: 0) + (it.fields[PerfKeys.OUT_TOKENS] ?: 0) }
        val diskBytes = Files.size(history.file) + Files.size(history.file.resolveSibling("${history.file.fileName}.1"))
        println(
            "perf_window_requests=${rows.size} perf_window_tokens=$tokens response_bytes=$bytes " +
                "source_file_bytes=$diskBytes parsed_lines=${source.parsedLines} retained_bytes=${source.cachedBytes}",
        )
        assertEquals(SCALE_REQUESTS, rows.size, "the display limit must never truncate the usage window")
        assertEquals(SCALE_TOKENS, tokens, "the large token total must not overflow or lose rows")
        assertTrue(
            source.parsedLines <= SCALE_REQUESTS + 8L,
            "the cold decode budget belongs to the requested window plus bounded retention and drop anchors",
        )
        val coldParses = source.parsedLines
        val warmRows = profiler.source(source, dir.resolve("warm-source.jfr"))
        assertEquals(rows, warmRows, "warm selection must preserve every requested row and field")
        assertTrue(source.parsedLines - coldParses <= SCALE_REQUESTS + 8L)
        val requestedBytes = Files.size(history.file)
        assertTrue(profiler.diskBytes > 0, "the byte instrument must observe the forced window-prefix replay")
        assertTrue(
            profiler.diskBytes <= requestedBytes + 65_536L,
            "unchanged reads may replay the requested window, never hash or frame the older generation",
        )
        println("perf_warm_disk_bytes=${profiler.diskBytes} requested_file_bytes=$requestedBytes")
        assertTrue(
            profiler.sourceBytes <= profiler.diskBytes * 6,
            "warm allocation must stay within a small multiple of the requested bytes actually replayed",
        )
    }
}
