// NEW: V4-133 — PerfStats' opt-in archive: a rotated-out generation is copied into archiveDir,
// timestamped, before JsonlSink would otherwise discard it; the sweep evicts generations past the
// person's history window and keeps the rest. maxBytes = 1 forces every record() past the first to
// rotate, so the sequence needs only a handful of rows rather than 64 MB of turns.
//
// Oct 10, 2026: the window is the one setting a person sets for their whole history (Settings > Your
// data), so the last case here is the one that was unreachable before — they chose to keep
// everything, and the sweep has nothing it may delete.
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.HistoryWindow
import splice.core.perf.TurnPerf
import splice.core.util.AsyncFileIo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val DAY_MS = 24L * 60 * 60 * 1000

class PerfStatsArchiveTest {

    private fun meta() = PerfRowMeta(model = "m", outcome = "ok", compact = false)
    private fun row(stats: PerfStats) = stats.record(meta(), TurnPerf { 0L }.snapshot())

    @Test
    fun `a rotated generation is archived and timestamped before JsonlSink would discard it`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val archiveDir = tmp.resolve("archive")
        var now = 1_000L
        val stats = PerfStats(file, clock = { now }, archiveDir = archiveDir, window = HistoryWindow(30), maxBytes = 1)

        row(stats)
        now += 1_000
        row(stats) // rotates row 1 out; no .1 existed yet, so nothing is archived
        now += 1_000
        row(stats) // rotates row 2 out; the .1 it replaces (row 1) is archived first
        assertTrue(AsyncFileIo.drain()) // settle writes explicitly before this test reads the filesystem

        assertTrue(Files.isDirectory(archiveDir), "the archive dir is created on the first archived rotation")
        val archived = Files.list(archiveDir).use { it.toList() }
        assertEquals(1, archived.size, "one generation rotated out with a .1 already there to archive")
        assertTrue(archived.single().fileName.toString().startsWith("perf.jsonl-"), archived.single().toString())
    }

    @Test
    fun `the sweep evicts an archived generation past the window and keeps the rest`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val archiveDir = tmp.resolve("archive")
        var now = 0L
        val stats = PerfStats(file, clock = { now }, archiveDir = archiveDir, window = HistoryWindow(5), maxBytes = 1)

        row(stats)
        now += DAY_MS
        row(stats) // rotates, no .1 yet
        now += DAY_MS
        row(stats) // rotates, archives the first generation
        assertTrue(AsyncFileIo.drain())
        val firstArchived = Files.list(archiveDir).use { it.toList() }.single()

        // Age that archived file past the 5-day window relative to where the clock is about to go,
        // then trigger one more rotation so sweepArchive runs again and must evict it.
        Files.setLastModifiedTime(firstArchived, FileTime.fromMillis(now - 10 * DAY_MS))
        now += DAY_MS
        row(stats) // rotates, archives the second generation, and sweeps
        assertTrue(AsyncFileIo.drain())

        val remaining = Files.list(archiveDir).use { it.toList() }
        assertEquals(1, remaining.size, "the stale generation was evicted; the fresh one was kept")
        assertTrue(Files.notExists(firstArchived), "the specific stale file is gone, not just outnumbered")
        assertNotEquals(firstArchived.fileName, remaining.single().fileName, "the survivor is the NEW generation")
    }

    @Test
    fun `a person who keeps everything keeps every archived generation`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val archiveDir = tmp.resolve("archive")
        var now = 0L
        val forever = HistoryWindow(null)
        val stats = PerfStats(file, clock = { now }, archiveDir = archiveDir, window = forever, maxBytes = 1)

        row(stats)
        now += DAY_MS
        row(stats)
        now += DAY_MS
        row(stats) // archives the first generation
        assertTrue(AsyncFileIo.drain())
        val archived = Files.list(archiveDir).use { it.toList() }.single()

        // A year on, with the file as old as the install: a window that keeps everything has no
        // horizon to compare it against, so the sweep must leave it where it is.
        Files.setLastModifiedTime(archived, FileTime.fromMillis(now - 365 * DAY_MS))
        now += DAY_MS
        row(stats)
        assertTrue(AsyncFileIo.drain())

        assertTrue(Files.exists(archived), "forever deletes nothing, however old the generation is")
        assertEquals(2, Files.list(archiveDir).use { it.toList() }.size, "and the new generation lands beside it")
    }
}
