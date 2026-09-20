// NEW: V4-133 — PerfStats' opt-in archive: a rotated-out generation is copied into archiveDir,
// timestamped, before JsonlSink would otherwise discard it; the sweep evicts generations past
// archiveRetentionDays and keeps the rest. maxBytes = 1 forces every record() past the first to
// rotate, so the sequence needs only a handful of rows rather than 64 MB of turns.
package console.v4133

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.TurnPerf
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
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
        val stats = PerfStats(file, clock = { now }, archiveDir = archiveDir, archiveRetentionDays = 30, maxBytes = 1)

        row(stats)
        now += 1_000
        row(stats) // rotates row 1 out; no .1 existed yet, so nothing is archived
        now += 1_000
        row(stats) // rotates row 2 out; the .1 it replaces (row 1) is archived first
        stats.tailNumeric(1) // drains the async file-IO lane before this test reads the filesystem

        assertTrue(Files.isDirectory(archiveDir), "the archive dir is created on the first archived rotation")
        val archived = Files.list(archiveDir).use { it.toList() }
        assertEquals(1, archived.size, "one generation rotated out with a .1 already there to archive")
        assertTrue(archived.single().fileName.toString().startsWith("perf.jsonl-"), archived.single().toString())
    }

    @Test
    fun `the sweep evicts an archived generation past archiveRetentionDays and keeps the rest`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val archiveDir = tmp.resolve("archive")
        var now = 0L
        val stats = PerfStats(file, clock = { now }, archiveDir = archiveDir, archiveRetentionDays = 5, maxBytes = 1)

        row(stats)
        now += DAY_MS
        row(stats) // rotates, no .1 yet
        now += DAY_MS
        row(stats) // rotates, archives the first generation
        stats.tailNumeric(1)
        val firstArchived = Files.list(archiveDir).use { it.toList() }.single()

        // Age that archived file past the 5-day window relative to where the clock is about to go,
        // then trigger one more rotation so sweepArchive runs again and must evict it.
        Files.setLastModifiedTime(firstArchived, FileTime.fromMillis(now - 10 * DAY_MS))
        now += DAY_MS
        row(stats) // rotates, archives the second generation, and sweeps
        stats.tailNumeric(1)

        val remaining = Files.list(archiveDir).use { it.toList() }
        assertEquals(1, remaining.size, "the stale generation was evicted; the fresh one was kept")
        assertTrue(Files.notExists(firstArchived), "the specific stale file is gone, not just outnumbered")
        assertNotEquals(firstArchived.fileName, remaining.single().fileName, "the survivor is the NEW generation")
    }
}
