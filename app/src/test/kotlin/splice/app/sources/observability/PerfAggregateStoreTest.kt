package splice.app.sources.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class PerfAggregateStoreTest {
    private val identity = PerfIdentity("boot-a", "build-a", "1", "config-a")
    private val minute = 60_000L

    @Test
    fun `repeated cumulative checkpoints replace and older commits cannot roll back retained totals`(
        @TempDir directory: Path,
    ) {
        val store = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        append(store, directory, snapshot(minute, 2, 90_000L))
        append(store, directory, snapshot(minute, 5, 110_000L))
        append(store, directory, snapshot(minute, 5, 110_000L))
        append(store, directory, snapshot(minute, 2, 90_000L))
        val result = store.read(minute, 120_000L)
        try {
            assertEquals(5L, result.snapshots.flatMap { it.points }.sumOf { it.count })
            assertEquals(50.0, result.snapshots.flatMap { it.points }.sumOf { it.sum })
        } finally {
            PerfWorkBudget.release(result)
        }
        assertEquals(4L, store.status().written)
        store.close()
        val offline = PerfAggregateStore(directory, identity.copy(bootId = "boot-b"), WallClock { 200_000L })
        val reopened = offline.read(minute, 120_000L)
        try {
            assertEquals(5L, reopened.snapshots.flatMap { it.points }.sumOf { it.count })
        } finally {
            PerfWorkBudget.release(reopened)
        }
    }

    @Test
    fun `older minutes become one hourly rollup without adding repeated checkpoints`(@TempDir directory: Path) {
        var now = 200_000L
        val store = PerfAggregateStore(directory, identity, WallClock { now })
        append(store, directory, snapshot(minute, 2, 100_000L))
        append(store, directory, snapshot(2 * minute, 3, 170_000L))
        now = 27L * PERF_HOUR_MS
        append(store, directory, snapshot(now, 1, now + 1_000))
        val result = store.read(0L, PERF_HOUR_MS)
        try {
            assertEquals(PERF_HOUR_MS, result.resolutionMs)
            assertEquals(5L, result.snapshots.flatMap { it.points }.sumOf { it.count })
        } finally {
            PerfWorkBudget.release(result)
        }
        append(store, directory, snapshot(minute, 9, 110_000L))
        val late = store.read(0L, PERF_HOUR_MS)
        try {
            assertEquals(5L, late.snapshots.flatMap { it.points }.sumOf { it.count })
            assertTrue(late.coverage.issues.any { it.code == "late_checkpoint_expired" })
        } finally {
            PerfWorkBudget.release(late)
        }
        store.close()
    }

    @Test
    fun `restart uncertainty build changes and absent intervals remain visible`(@TempDir directory: Path) {
        val store = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        append(store, directory, snapshot(minute, 1, 100_000L))
        val restarted =
            PerfAggregateStore(directory, identity.copy(bootId = "new", build = "new"), WallClock { 200_000L })
        val read = restarted.read(0L, 200_000L)
        try {
            assertTrue(read.coverage.issues.any { it.code == "checkpoint_uncertain" })
            assertFalse(read.coverage.issues.any { it.code == "build_changed" })
            assertTrue(read.coverage.issues.any { it.code == "coverage_gap" })
            assertTrue(read.coverage.observedMs < 200_000L)
        } finally {
            PerfWorkBudget.release(read)
        }
        store.close()
        assertTrue(restarted.openWriter())
        val changed = restarted.read(0L, 200_000L)
        try {
            assertTrue(changed.coverage.issues.any { it.code == "build_changed" })
            assertTrue(changed.coverage.issues.any { it.code == "boot_changed" })
        } finally {
            PerfWorkBudget.release(changed)
            restarted.close()
        }
    }

    @Test
    fun `a clean offline reader preserves writer identity without inventing its own transition`(@TempDir dir: Path) {
        PerfAggregateStore(dir, identity, WallClock { 120_000L }).use {
            append(it, dir, snapshot(minute, 110, 120_000L))
        }
        val checkpoint = Files.readString(dir.resolve("checkpoint.json"))
        val readerIdentity = PerfIdentity("offline", "unknown", "other-version", "unknown")
        PerfAggregateStore(dir, readerIdentity, WallClock { 120_000L }).use { reader ->
            val read = reader.read(minute, 120_000L)
            try {
                assertTrue(read.coverage.issues.isEmpty(), read.coverage.issues.toString())
                assertEquals(listOf(identity), read.snapshots.map { it.identity }.distinct())
                assertEquals(110L, read.snapshots.flatMap { it.points }.sumOf { it.count })
            } finally {
                PerfWorkBudget.release(read)
            }
        }
        assertEquals(checkpoint, Files.readString(dir.resolve("checkpoint.json")))
    }

    @Test
    fun `failed appends remain failed and malformed retained files are not quiet history`(@TempDir directory: Path) {
        val blocked = directory.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val failed = PerfAggregateStore(blocked, identity, WallClock { 200_000L })
        assertFalse(failed.append(snapshot(minute, 1, 100_000L)))
        assertEquals(1L, failed.status().dropped)
        assertEquals(0L, failed.status().failed)
        assertEquals(0L, failed.status().written)
        Files.writeString(directory.resolve("minute-60000.json"), "{broken")
        val offline = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        val malformed = offline.read(minute, 120_000L)
        try {
            assertTrue(malformed.coverage.issues.any { it.code == "history_malformed" })
        } finally {
            PerfWorkBudget.release(malformed)
        }
    }

    @Test
    fun `retained history expires offline and oversized checkpoints report refused persistence`(
        @TempDir directory: Path,
    ) {
        val store = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        append(store, directory, snapshot(minute, 1, 100_000L))
        store.close()
        val offline = PerfAggregateStore(directory, identity, WallClock { 40L * PERF_DAY_MS })
        val expired = offline.read(minute, 120_000L)
        try {
            assertTrue(expired.snapshots.isEmpty())
            assertTrue(expired.coverage.issues.any { it.code == "retention_expired" })
        } finally {
            PerfWorkBudget.release(expired)
        }
        val enormous = snapshot(minute, 1, 100_000L).let { value ->
            value.copy(points = List(10_000) { value.points.single() })
        }
        assertFalse(offline.append(enormous))
        assertEquals(1L, offline.status().dropped)
        assertEquals(0L, offline.status().enqueued)
    }

    private fun append(store: PerfAggregateStore, directory: Path, value: PerfAggregateSnapshot) {
        assertTrue(store.append(value))
        assertTrue(AsyncFileIo.awaitFile(directory.resolve("checkpoint.json")))
    }

    private fun snapshot(from: Long, count: Long, until: Long): PerfAggregateSnapshot = PerfAggregateSnapshot(
        identity,
        from,
        until,
        listOf(
            PerfAggregatePoint(
                "arrival_to_terminal_ms",
                PerfScope(head = "test"),
                from,
                until,
                count,
                count * 10.0,
                10.0,
                10.0,
            ),
        ),
        PerfCoverage(from, until, until - from),
    )
}
