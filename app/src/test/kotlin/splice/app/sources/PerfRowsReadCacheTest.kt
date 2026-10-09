// Parse-count and bounded-retention regressions for coherent perf window reads.
package splice.app.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfArchiveName
import splice.usage.perf.PerfRow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PerfRowsReadCacheTest {
    @Test
    fun `identical reads decode each appended row once across windows and economics`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(dir.resolve("head-perf.jsonl.1"), row(1))
        Files.writeString(file, row(2) + row(3))
        val source = PerfRowsFileSource(file)

        val first = source.window(0)
        val decoded = source.parsedLines
        assertEquals(3L, decoded, "the cold read decodes the source rows")
        assertEquals(first, source.window(0))
        assertEquals(decoded, source.parsedLines, "an identical read must perform zero new JSON decodes")
        assertEquals(listOf(2L, 3L), source.window(2).rows.map { it.ts })
        assertEquals(decoded, source.parsedLines, "a different cutoff uses the retained form")
        assertEquals(first, source.economicsEvidence(0).work)
        assertEquals(decoded, source.parsedLines, "economics shares the same parsed generations")

        Files.writeString(file, row(4), APPEND)
        assertEquals(listOf(1L, 2L, 3L, 4L), source.window(0).rows.map { it.ts })
        assertEquals(decoded + 1, source.parsedLines, "only the append is decoded")
        assertTrue(source.window(4).readError == null)
        assertEquals(decoded + 1, source.parsedLines)
    }

    @Test
    fun `rotation reuses cached file identities across live rotated and archived names`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val rotated = dir.resolve("head-perf.jsonl.1")
        val archive = Files.createDirectory(dir.resolve("archive"))
        Files.writeString(rotated, row(1))
        Files.writeString(file, row(2))
        val source = PerfRowsFileSource(file, archive)
        assertEquals(listOf(1L, 2L), source.window(0).rows.map { it.ts })
        assertEquals(2L, source.parsedLines)

        Files.move(rotated, archive.resolve(PerfArchiveName(file.fileName.toString()).of(10_000)))
        Files.move(file, rotated)
        Files.writeString(file, row(3))
        assertEquals(listOf(1L, 2L, 3L), source.window(0).rows.map { it.ts })
        assertEquals(3L, source.parsedLines, "only the new live generation is decoded")
        assertEquals(listOf(2L, 3L), source.window(2).rows.map { it.ts })
        assertEquals(3L, source.parsedLines)
    }

    @Test
    fun `an unchanged torn tail is reused and a completed tail replaces its old facts`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, row(1).trimEnd())
        val source = PerfRowsFileSource(file)
        assertEquals(listOf(1L), source.window(0).rows.map { it.ts })
        Files.writeString(file, "\n" + row(2), APPEND)
        assertEquals(listOf(1L, 2L), source.window(0).rows.map { it.ts })
        assertEquals(2L, source.parsedLines, "a terminator alone does not decode the same row again")

        Files.writeString(file, """{"ts":3,"outcome":"ok"""", APPEND)
        assertEquals(1, source.window(0).skipped)
        assertEquals(3L, source.parsedLines)
        assertEquals(1, source.window(0).skipped)
        assertEquals(3L, source.parsedLines, "an unchanged malformed last line is not retried")
        Files.writeString(file, "}" + "\n", APPEND)
        assertEquals(listOf(1L, 2L, 3L), source.window(0).rows.map { it.ts })
        assertEquals(0, source.window(0).skipped)
        assertEquals(4L, source.parsedLines, "only the changed last line is decoded")
    }

    @Test
    fun `simultaneous tabs share one decode and keep independent result lists`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, (1L..30L).joinToString("") { row(it) })
        val source = PerfRowsFileSource(file)
        val tabs = Executors.newFixedThreadPool(4)
        try {
            val reads = tabs.invokeAll(List(8) { Callable { source.window(0) } })
                .map { it.get(10, TimeUnit.SECONDS) }
            reads.forEach { assertEquals(reads.first(), it) }
            assertEquals(30L, source.parsedLines, "poll concurrency cannot duplicate the JSON decode")
            assertTrue(reads[0].rows !== reads[1].rows)
        } finally {
            tabs.shutdownNow()
        }
    }

    @Test
    fun `cached hints keep the original pre-cutoff counter authority`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            row(1) + """{"ts":2,"ts":"invalid","async_io_drops":8}""" + "\n" + row(5),
        )
        val source = PerfRowsFileSource(file)
        val first = source.window(3)
        assertEquals(listOf(5L), first.rows.map { it.ts })
        assertEquals(8L, first.dropsBefore)
        assertEquals(first, source.window(3))
        assertEquals(3L, source.parsedLines)
    }

    @Test
    fun `a growing same-inode repair invalidates the former compact generation`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, row(1) + row(2))
        val source = PerfRowsFileSource(file)
        assertEquals(listOf(1L, 2L), source.window(0).rows.map { it.ts })
        Files.writeString(file, row(9) + row(10) + row(11))
        assertEquals(listOf(9L, 10L, 11L), source.window(0).rows.map { it.ts })
        val parsed = source.parsedLines
        assertEquals(listOf(9L, 10L, 11L), source.window(0).rows.map { it.ts })
        assertEquals(parsed, source.parsedLines)
    }

    @Test
    fun `line endings remain coherent when an appended LF completes a former CR`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, row(1).trimEnd() + "\r")
        val source = PerfRowsFileSource(file)
        assertEquals(listOf(1L), source.window(0).rows.map { it.ts })
        Files.writeString(file, "\n" + row(2).trimEnd() + "\r" + row(3), APPEND)
        val read = source.window(0)
        assertEquals(listOf(1L, 2L, 3L), read.rows.map { it.ts })
        assertEquals(0, read.skipped, "the new LF is the old CRLF terminator, not an empty row")
        assertEquals(3L, source.parsedLines)
        assertEquals(read, source.window(0))
        assertEquals(3L, source.parsedLines)
    }

    @Test
    fun `a write during decode cannot seal old facts under the rewritten bytes digest`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, row(1) + row(2))
        val cache = PerfRowsCache()
        val decoder = PerfLineDecode { line ->
            val fields = cache.fields(Json.parseToJsonElement(line).jsonObject)
            val ts = requireNotNull(fields["ts"])
            PerfCachedLine(
                row = PerfRow(ts = ts, outcome = "ok", fields = fields),
                numericBytes = fields.retainedBytes,
                leadingTs = ts,
                emptyModel = false,
                drops = PerfDropsHint(candidate = false, count = null),
                probe = false,
            )
        }
        var changed = false
        val writer = object : PerfLineVisit {
            override fun raw(line: String) = Unit
            override fun kept(line: PerfCachedLine) {
                if (!changed) {
                    Files.writeString(file, row(9) + row(8) + row(3))
                    changed = true
                }
            }
        }
        cache.read(file, 0, writer, decoder)
        val actual = mutableListOf<Long>()
        val reader = object : PerfLineVisit {
            override fun raw(line: String) {
                actual += requireNotNull(decoder.decode(line).row).ts
            }
            override fun kept(line: PerfCachedLine) {
                actual += requireNotNull(line.row).ts
            }
        }
        cache.read(file, 0, reader, decoder)
        assertEquals(listOf(9L, 8L, 3L), actual, "the next read must recover the actual rewritten prefix")
    }

    @Test
    fun `the parsed baseline preserves a quoted legacy counter without changing the numeric bag`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, """{"ts":1,"async_io_drops":"8"}""" + "\n" + row(5))
        val source = PerfRowsFileSource(file)
        val read = source.window(3)
        assertEquals(8L, read.dropsBefore)
        assertEquals(read, source.window(3))
        assertEquals(2L, source.parsedLines)
        assertEquals(null, source.window(0).rows.first().fields["async_io_drops"])
    }

    private fun row(ts: Long): String =
        """{"ts":$ts,"outcome":"ok","model":"synthetic-model","input_tokens":12}""" + "\n"
}

class PerfRowsCacheIntegrityTest {
    @Test
    fun `same-size repairs remain visible when the modification time is restored`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, "{\"ts\":1}\n{\"ts\":2}\n")
        val modified = Files.getLastModifiedTime(file)
        val source = PerfRowsFileSource(file)
        assertEquals(listOf(1L, 2L), source.window(0).rows.map { it.ts })
        Files.writeString(file, "{\"ts\":9}\n{\"ts\":8}\n")
        Files.setLastModifiedTime(file, modified)
        assertEquals(listOf(9L, 8L), source.window(0).rows.map { it.ts })
        assertEquals(4L, source.parsedLines)
        source.window(0)
        assertEquals(4L, source.parsedLines, "a stamp-preserving repair is decoded once")
    }

    @Test
    fun `same-stamp unterminated repairs reuse only identical tail bytes`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, "{\"ts\":1}")
        val modified = Files.getLastModifiedTime(file)
        val source = PerfRowsFileSource(file)
        assertEquals(listOf(1L), source.window(0).rows.map { it.ts })
        Files.writeString(file, "{\"ts\":9}")
        Files.setLastModifiedTime(file, modified)
        assertEquals(listOf(9L), source.window(0).rows.map { it.ts })
        source.window(0)
        assertEquals(2L, source.parsedLines, "an identical repaired tail does not decode again")
    }
}
