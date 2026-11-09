package splice.app.instrumentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.observability.PerfCleanup
import splice.app.observability.PerfCollection
import splice.app.observability.PerfConfigHistory
import splice.app.sources.PerfSourceFiles
import splice.app.sources.observability.PerfHistorySize
import splice.core.config.StatePaths
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.core.util.PerfWorkPhase
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class PerfPagedCheckpointTest {
    @Test
    fun `all retained minutes reach history even when their combined snapshot exceeds one write budget`(
        @TempDir root: Path,
    ) {
        PagedHistoryFixture(root).use { fixture ->
            fixture.seed(12)
            assertTrue(PerfHistorySize.snapshot(fixture.collection.snapshot()) > 4L * 1024 * 1024)
            repeat(15) { fixture.checkpoint() }
            repeat(12) { fixture.assertMinute(it + 1) }
            assertEquals(0L, fixture.history.status().dropped)
            assertEquals(0L, fixture.history.status().failed)
        }
    }

    @Test
    fun `one failed minute does not prevent later history and is retried after the obstruction clears`(
        @TempDir root: Path,
    ) {
        PagedHistoryFixture(root).use { fixture ->
            fixture.seed(2)
            val obstruction = fixture.paths.perfAggregatesDir.resolve("minute-60000.json")
            Files.createDirectories(obstruction)
            repeat(3) { fixture.checkpoint() }
            fixture.assertMinute(2)
            assertTrue(fixture.history.status().failed > 0)
            Files.delete(obstruction)
            repeat(3) { fixture.checkpoint() }
            fixture.assertMinute(1)
            fixture.assertMinute(2)
        }
    }

    @Test
    fun `an unchanged gauge fills completed minutes after an earlier partial checkpoint`(@TempDir root: Path) {
        PagedHistoryFixture(root).use { fixture ->
            fixture.gauge()
            fixture.advanceTo(90_000L)
            fixture.checkpoint()
            fixture.advanceTo(210_000L)
            repeat(4) { fixture.checkpoint() }
            fixture.assertGaugeCoverage(1, 60_000L)
            fixture.assertGaugeCoverage(2, 60_000L)
        }
    }

    @Test
    fun `final checkpoint drains retained history before owners close`(@TempDir root: Path) {
        PagedHistoryFixture(root).use { fixture ->
            fixture.seed(3)
            fixture.checkpointFinal()
            repeat(3) { fixture.assertMinute(it + 1) }
        }
    }

    @Test
    fun `durable cache eviction preserves history without claiming lost gauge input`(@TempDir root: Path) {
        PagedHistoryFixture(root).use { fixture ->
            fixture.gauge()
            repeat(20) {
                fixture.seedMinute(it + 1)
                repeat(3) { fixture.checkpoint() }
            }
            fixture.assertMinute(1)
            fixture.assertMinute(20)
            val issues = fixture.collection.snapshot().coverage.issues
            assertTrue(issues.none { it.code == "late_observation_expired" }, issues.toString())
        }
    }
}

private class PagedHistoryFixture(root: Path) : AutoCloseable {
    val paths = StatePaths(baseOverride = root)
    private var now = 60_000L
    private val clock = WallClock { now }
    private val elapsed = ElapsedClock { now }
    private val identity = PerfIdentity("paged-boot", "paged-build", "1", "config")
    private val cleanup = PerfCleanup(LogSink {})
    private val config = PerfConfigHistory(paths.perfConfigurationFile)
    val collection = PerfCollection(paths, identity, setOf("test"), PerfSourceFiles(paths), true, clock, elapsed)
    val history = collection.sources(config).history()
    private val recorder = PerfRecorder(identity, collection.fanout(NoPerfObserver), elapsed, clock)

    init { collection.start() }

    fun seed(minutes: Int) {
        repeat(minutes) { seedMinute(it + 1) }
    }

    fun seedMinute(minute: Int) {
        repeat(64) { model ->
            now = minute * 60_000L + model
            assertTrue(
                recorder.emit(
                    PerfScope(head = "test", model = "model-${"a".repeat(80)}-$model", transport = "http"),
                    PerfEventKind.RUNTIME,
                    mapOf("runtime_heap_used_bytes" to 100.0, "runtime_threads" to 2.0),
                ),
            )
        }
    }

    fun checkpoint() {
        requireNotNull(PerfWorkPhase.tryCheckpoint()).use { collection.checkpoint(it) }
        // The barrier can report the deliberately earlier failed write while still draining every queued page.
        AsyncFileIo.awaitFile(paths.perfAggregatesDir.resolve("checkpoint.json"))
    }

    fun checkpointFinal() {
        requireNotNull(PerfWorkPhase.tryCheckpoint()).use { collection.checkpointFinal(it) }
    }

    fun gauge() {
        assertTrue(recorder.emit(PerfScope(head = "test"), PerfEventKind.RUNTIME, mapOf("runtime_threads" to 2.0)))
    }

    fun advanceTo(at: Long) {
        now = at
        assertTrue(recorder.emit(PerfScope(head = "test"), PerfEventKind.SELF_COST))
    }

    fun assertGaugeCoverage(minute: Int, expected: Long) {
        val from = minute * 60_000L
        val result = history.read(from, from + 60_000L)
        try {
            val point = result.snapshots.flatMap { it.points }.single { it.metric == "runtime_threads" }
            assertEquals(expected, point.coveredMs)
            assertEquals(expected * 2.0, point.weightedSum)
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    fun assertMinute(minute: Int) {
        val from = minute * 60_000L
        val result = history.read(from, from + 60_000L)
        try {
            val points = result.snapshots.flatMap { it.points }
            assertTrue(result.coverage.issues.none { it.code == "clock_or_checkpoint_reordered" })
            assertEquals(
                64,
                points.filter { it.metric == "runtime_heap_used_bytes" }.map { it.scope.model }.distinct().size,
            )
            assertEquals(
                64,
                points.filter { it.metric == "runtime_threads" && it.scope.model != null }
                    .map { it.scope.model }.distinct().size,
            )
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    override fun close() {
        collection.closePersistence(cleanup)
        collection.close()
        config.close()
    }
}
