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
        assertEquals(5L, result.snapshots.flatMap { it.points }.sumOf { it.count })
        assertEquals(50.0, result.snapshots.flatMap { it.points }.sumOf { it.sum })
        assertEquals(4L, store.status().written)
        store.close()
        val offline = PerfAggregateStore(directory, identity.copy(bootId = "boot-b"), WallClock { 200_000L })
        assertEquals(5L, offline.read(minute, 120_000L).snapshots.flatMap { it.points }.sumOf { it.count })
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
        assertEquals(PERF_HOUR_MS, result.resolutionMs)
        assertEquals(5L, result.snapshots.flatMap { it.points }.sumOf { it.count })
        append(store, directory, snapshot(minute, 9, 110_000L))
        val late = store.read(0L, PERF_HOUR_MS)
        assertEquals(5L, late.snapshots.flatMap { it.points }.sumOf { it.count })
        assertTrue(late.coverage.issues.any { it.code == "late_checkpoint_expired" })
        store.close()
    }

    @Test
    fun `restart uncertainty build changes and absent intervals remain visible`(@TempDir directory: Path) {
        val store = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        append(store, directory, snapshot(minute, 1, 100_000L))
        val restarted =
            PerfAggregateStore(directory, identity.copy(bootId = "new", build = "new"), WallClock { 200_000L })
        val read = restarted.read(0L, 200_000L)
        assertTrue(read.coverage.issues.any { it.code == "checkpoint_uncertain" })
        assertTrue(read.coverage.issues.any { it.code == "build_changed" })
        assertTrue(read.coverage.issues.any { it.code == "coverage_gap" })
        assertTrue(read.coverage.observedMs < 200_000L)
        store.close()
    }

    @Test
    fun `failed appends remain failed and malformed retained files are not quiet history`(@TempDir directory: Path) {
        val blocked = directory.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val failed = PerfAggregateStore(blocked, identity, WallClock { 200_000L })
        assertTrue(failed.append(snapshot(minute, 1, 100_000L)))
        assertFalse(AsyncFileIo.awaitFile(blocked.resolve("checkpoint.json")))
        assertEquals(1L, failed.status().failed)
        assertEquals(0L, failed.status().written)
        Files.writeString(directory.resolve("minute-60000.json"), "{broken")
        val offline = PerfAggregateStore(directory, identity, WallClock { 200_000L })
        assertTrue(offline.read(minute, 120_000L).coverage.issues.any { it.code == "history_malformed" })
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
