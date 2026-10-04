// NEW: parse-count and bounded-retention regressions for coherent perf window reads.
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
    fun `byte eviction keeps complete windows while recent retained rows need no decoding`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, (1L..100L).joinToString("") { row(it) })
        val budget = 32_768L
        val source = PerfRowsFileSource(file, cache = PerfRowsCache(budget))
        val all = source.window(0)
        assertEquals(100, all.rows.size)
        assertTrue(source.cachedBytes <= budget)
        assertTrue(source.cachedLines < all.rows.size, "the pressure control must force eviction")
        assertEquals(all, source.window(0), "eviction cannot truncate the requested window")
        val before = source.parsedLines
        val recent = source.window(99)
        assertEquals(listOf(99L, 100L), recent.rows.map { it.ts })
        assertEquals(1L, source.parsedLines - before, "only the evicted retention anchor is parsed")
        assertEquals(1L, recent.oldestHeldTs)
        assertTrue(source.cachedBytes <= budget)
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
    fun `one reported day of representative rows fits the compact byte and row caps`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        // The lead's aggregate-only Oct 3 measurement: the largest source appended 27,537 rows,
        // averaging 1,082 JSON bytes. This synthetic shape matches that mean without private rows.
        val count = 27_537
        val lines = (1..count).map { representative(it.toLong()) }
        val wireBytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        Files.writeString(file, lines.joinToString(""))
        val source = PerfRowsFileSource(file)
        val first = source.window(0)
        assertEquals(count, first.rows.size)
        assertEquals(count, source.cachedLines, "the calibrated recent day must not spill past the byte cap")
        assertTrue(source.cachedBytes <= PERF_CACHE_BYTES)
        assertTrue(source.cachedLines <= PERF_CACHE_ROWS)
        assertEquals(first, source.window(0))
        assertEquals(count.toLong(), source.parsedLines, "the entire representative day is decoded once")
        println(
            "perf_cache_rows=$count wire_bytes=$wireBytes cached_bytes=${source.cachedBytes} " +
                "charged_bytes_per_row=${source.cachedBytes / count} " +
                "first_read_parses=$count repeated_read_parses=0 byte_cap=$PERF_CACHE_BYTES row_cap=$PERF_CACHE_ROWS",
        )
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
                dropsCandidate = false,
                drops = null,
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

    private fun representative(ts: Long): String {
        val fields = (0 until 35).joinToString(",") { index -> """"metric_${index.toString().padStart(2, '0')}":12""" }
        return """{"ts":$ts,"outcome":"ok","model":"synthetic-model","session":"synthetic","session_id":"synthetic-session-full-identity","response_message_id":"synthetic-response","account":"synthetic-account","turn":"synthetic-turn","compact":false,"cache_cold":true,"input_tokens":12,$fields,"ignored_detail":"${"x".repeat(265)}"}""" + "\n"
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

    @Test
    fun `evicted generations cannot hide backing storage outside the byte cap`(@TempDir dir: Path) {
        val budget = 1_024L * 1_024
        val cache = PerfRowsCache(budget)
        val visit = object : PerfLineVisit {
            override fun raw(line: String) = Unit
            override fun kept(line: PerfCachedLine) = Unit
        }
        val empty = PerfCachedLine(null, 0L, null, false, false, null, false)
        val decode = PerfLineDecode { empty }
        val files = (0 until 64).map { index ->
            dir.resolve("generation-$index.jsonl").also {
                Files.writeString(it, "{}\n".repeat(2_048))
                cache.read(it, index, visit, decode)
            }
        }
        val outcome = "Ā".repeat(400 * 1_024)
        Files.writeString(files.last(), "{\"ts\":1}\n")
        cache.read(
            files.last(),
            files.lastIndex,
            visit,
            PerfLineDecode { empty.copy(row = PerfRow(ts = 1, outcome = outcome, fields = emptyMap())) },
        )
        val queueSlots = retainedQueueSlots(cache)
        val minimumBytes = queueSlots * 4L + outcome.length * 2L
        assertTrue(cache.retainedBytes <= budget)
        assertTrue(
            minimumBytes <= budget,
            "queue arrays and non-Latin string storage alone retain $minimumBytes bytes above the $budget-byte cap",
        )
        println(
            "perf_cache_pressure_slots=$queueSlots minimum_bytes=$minimumBytes charged_bytes=${cache.retainedBytes}",
        )
    }

    private fun retainedQueueSlots(cache: PerfRowsCache): Long {
        val generations = cache.javaClass.getDeclaredField("generations").apply { isAccessible = true }
            .get(cache) as Map<*, *>
        return generations.values.filterNotNull().sumOf { generation ->
            val lines = generation.javaClass.getDeclaredField("lines").apply { isAccessible = true }.get(generation)
            lines.javaClass.declaredFields
                .filter { it.type.isArray && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .sumOf { field ->
                    val array = field.apply { isAccessible = true }.get(lines) as? Array<*>
                    array?.size?.toLong() ?: 0L
                }
        }
    }
}
