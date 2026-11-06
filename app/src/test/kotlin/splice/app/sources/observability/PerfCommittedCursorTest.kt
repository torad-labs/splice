package splice.app.sources.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.observability.cursor.PERF_CURSOR_TTL_MS
import splice.app.sources.observability.cursor.PerfCommittedCursors
import splice.core.perf.PerfArchiveName
import splice.core.util.WallClock
import splice.usage.perf.history.PerfCursorQuery
import splice.usage.perf.history.PerfCursorRequest
import splice.usage.perf.history.PerfRowPredicate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class PerfCommittedCursorTest {
    private val query = PerfCursorQuery(0L, 10_000L, "all")
    private val all = PerfRowPredicate { true }

    @Test
    fun `opaque start cursor replays the frozen first record after append and reader restart`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("head.jsonl")
        Files.writeString(file, row(500, "ok"))
        val request = PerfCursorRequest(query, all, pageSize = 1)
        val start = PerfRowsFileSource(file).page(request).use { first ->
            splice.core.util.JsonScalars.str(first.metadata, "start_cursor")
        }
        assertNotNull(start)
        Files.writeString(file, row(100, "ok"), StandardOpenOption.APPEND)
        PerfRowsFileSource(file).page(request.copy(cursor = start)).use { frozen ->
            assertEquals(listOf(500L), frozen.rows.map { it.ts })
            assertEquals("1", frozen.metadata["total_matching"].toString())
        }
    }

    @Test
    fun `fixed cursor counts all filters before paging and excludes late commits with older timestamps`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("head.jsonl")
        Files.writeString(file, row(500, "ok") + row(500, "failed") + row(300, "ok"))
        val source = PerfRowsFileSource(file)
        val request = PerfCursorRequest(query, all, pageSize = 1)
        val first = source.page(request)
        assertEquals("3", first.metadata["total_matching"].toString())
        assertEquals("\"complete\"", first.metadata["status"].toString())
        Files.writeString(file, row(100, "ok"), StandardOpenOption.APPEND)
        val second = source.page(request.copy(cursor = first.nextCursor))
        val third = source.page(request.copy(cursor = second.nextCursor))
        assertEquals(listOf(500L, 500L, 300L), (first.rows + second.rows + third.rows).map { it.ts })
        assertEquals(3, (first.recordIds + second.recordIds + third.recordIds).distinct().size)
        assertNull(third.nextCursor)
        val filtered = source.page(
            PerfCursorRequest(
                query.copy(filterHash = "failed"),
                PerfRowPredicate {
                    it.outcome == "failed"
                },
            ),
        )
        assertEquals("1", filtered.metadata["total_matching"].toString())
        assertEquals(listOf("failed"), filtered.rows.map { it.outcome })
    }

    @Test
    fun `continuation survives restart and copied archive rotation without duplicating records`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("head.jsonl")
        val archive = directory.resolve("archive")
        Files.createDirectories(archive)
        Files.writeString(file, row(500, "ok") + row(600, "ok"))
        val request = PerfCursorRequest(query, all, pageSize = 1)
        val first = PerfRowsFileSource(file, archive).page(request)
        assertNotNull(first.nextCursor)
        val rolled = file.resolveSibling("${file.fileName}.1")
        Files.move(file, rolled)
        Files.writeString(file, row(700, "ok"))
        Files.copy(rolled, archive.resolve(PerfArchiveName(file.fileName.toString()).of(1_000L)))
        Files.move(file, rolled, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(file, row(800, "ok"))
        val second = PerfRowsFileSource(file, archive).page(request.copy(cursor = first.nextCursor))
        assertEquals(listOf(600L), second.rows.map { it.ts })
        assertNull(second.nextCursor)
        val fresh = PerfRowsFileSource(file, archive).page(request.copy(pageSize = 20))
        assertEquals(4, fresh.rows.size)
        assertEquals(4, fresh.recordIds.distinct().size)
    }

    @Test
    fun `copied rolled alias counted once and changed filters missing segments and expired snapshots are explicit`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("head.jsonl")
        val archive = directory.resolve("archive")
        Files.createDirectories(archive)
        val rolled = file.resolveSibling("${file.fileName}.1")
        Files.writeString(rolled, row(500, "ok") + row(600, "ok"))
        val saved = archive.resolve(PerfArchiveName(file.fileName.toString()).of(1_000L))
        Files.copy(rolled, saved)
        var now = System.currentTimeMillis()
        val source = PerfCommittedCursors(file, archive, PerfSourceCosts(), WallClock { now })
        val request = PerfCursorRequest(query, all, pageSize = 1)
        val first = source.page(request)
        assertEquals("2", first.metadata["known_matching"].toString())
        assertEquals("\"incomplete\"", first.metadata["status"].toString())
        val changed = source.page(request.copy(cursor = first.nextCursor, query = query.copy(filterHash = "changed")))
        assertEquals("\"invalid\"", changed.metadata["status"].toString())
        now += PERF_CURSOR_TTL_MS + 1
        val expired = source.page(request.copy(cursor = first.nextCursor))
        assertEquals("\"expired\"", expired.metadata["status"].toString())
        now -= PERF_CURSOR_TTL_MS + 1
        Files.delete(rolled)
        Files.delete(saved)
        val missing = source.page(request.copy(cursor = first.nextCursor))
        assertEquals("\"expired\"", missing.metadata["status"].toString())
        assertTrue(missing.rows.isEmpty())
    }

    @Test
    fun `malformed and uncommitted rows are explicit and never called complete`(@TempDir directory: Path) {
        val file = directory.resolve("head.jsonl")
        Files.writeString(file, row(500, "ok") + "{malformed\n" + "{\"ts\":600,\"outcome\":\"ok\"}")
        val page = PerfRowsFileSource(file).page(PerfCursorRequest(query, all))
        assertEquals(1, page.rows.size)
        assertEquals("\"incomplete\"", page.metadata["status"].toString())
        assertEquals("null", page.metadata["total_matching"].toString())
        assertEquals("1", page.metadata["malformed_rows"].toString())
        assertTrue(page.metadata["read_errors"].toString().contains("uncommitted_tail"))
    }

    private fun row(ts: Long, outcome: String): String = "{\"ts\":$ts,\"model\":\"test\",\"outcome\":\"$outcome\"}\n"
}
