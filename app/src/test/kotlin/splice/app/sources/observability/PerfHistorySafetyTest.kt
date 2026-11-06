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
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerfHistorySafetyTest {
    private val identity = PerfIdentity("boot", "build", "1", "config")
    private val clock = WallClock { 120_000L }
    private val snapshot = PerfAggregateSnapshot(
        identity,
        60_000L,
        120_000L,
        listOf(
            PerfAggregatePoint(
                "arrival_to_terminal_ms",
                PerfScope(head = "test"),
                60_000L,
                120_000L,
                1,
                10.0,
                10.0,
                10.0,
            ),
        ),
        PerfCoverage(60_000L, 120_000L, 60_000L),
    )

    @Test
    fun `pending work after a clean close remains uncertain to an offline reader`(@TempDir dir: Path) {
        cleanHistory(dir)
        val checkpoint = Files.readString(dir.resolve("checkpoint.json"))
        PerfAggregateStore(dir, identity, clock).use { assertFalse(uncertain(it)) }
        assertEquals(checkpoint, Files.readString(dir.resolve("checkpoint.json")))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = PerfAggregateStore(dir, identity, clock)
        assertTrue(writer.openWriter())
        assertTrue(AsyncFileIo.submit { entered.countDown(); release.await(10, TimeUnit.SECONDS) })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            PerfAggregateStore(dir, identity, clock).use { assertTrue(uncertain(it)) }
            assertTrue(writer.append(snapshot))
            assertEquals(1L, writer.status().pending)
            PerfAggregateStore(dir, identity, clock).close()
            assertEquals(checkpoint, Files.readString(dir.resolve("checkpoint.json")))
        } finally {
            release.countDown()
            writer.close()
        }
        PerfAggregateStore(dir, identity, clock).use { assertFalse(uncertain(it)) }
    }

    @Test
    fun `failed work cannot restore a previously clean checkpoint`(@TempDir dir: Path) {
        cleanHistory(dir)
        Files.delete(dir.resolve("minute-60000.json"))
        Files.createDirectory(dir.resolve("minute-60000.json"))
        PerfAggregateStore(dir, identity, clock).use { writer ->
            assertTrue(writer.openWriter())
            assertTrue(writer.append(snapshot))
            assertFalse(AsyncFileIo.awaitFile(dir.resolve("checkpoint.json")))
            assertEquals(1L, writer.status().failed)
        }
        PerfAggregateStore(dir, identity, clock).use { assertTrue(uncertain(it)) }
    }

    @Test
    fun `variable checkpoint metadata is refused before enqueue`(@TempDir dir: Path) {
        val huge = "x".repeat(3 * 1_024 * 1_024)
        PerfAggregateStore(dir, identity, clock).use { writer ->
            val empty = snapshot.copy(points = emptyList())
            val sink = writer.status()
            val values = listOf(
                empty.copy(identity = identity.copy(build = huge)),
                empty.copy(coverage = snapshot.coverage.copy(issues = listOf(PerfIssue(huge, "source")))),
                empty.copy(coverage = snapshot.coverage.copy(issues = listOf(PerfIssue("code", huge)))),
                empty.copy(coverage = snapshot.coverage.copy(sinks = listOf(sink.copy(sink = huge)))),
                empty.copy(coverage = snapshot.coverage.copy(sinks = List(20_000) { sink })),
            )
            values.forEach { assertFalse(writer.append(it)) }
            assertEquals(0L, writer.status().enqueued)
            assertEquals(values.size.toLong(), writer.status().dropped)
        }
        PerfAggregateStore(dir, identity, clock).use { assertTrue(uncertain(it)) }
    }

    private fun cleanHistory(dir: Path) {
        PerfAggregateStore(dir, identity, clock).use {
            assertTrue(it.append(snapshot))
            assertTrue(AsyncFileIo.awaitFile(dir.resolve("checkpoint.json")))
        }
    }

    private fun uncertain(store: PerfAggregateStore): Boolean {
        val read = store.read(60_000L, 120_000L)
        return try {
            read.coverage.issues.any { it.code == "checkpoint_uncertain" }
        } finally {
            PerfWorkBudget.release(read)
        }
    }
}
