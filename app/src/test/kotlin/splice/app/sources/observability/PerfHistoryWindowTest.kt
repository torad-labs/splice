package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class PerfHistoryWindowTest {
    private val identity = PerfIdentity("boot", "build", "1", "config")

    @Test
    fun `a full day larger than one MiB returns every count and histogram at an explicit coarser resolution`(
        @TempDir dir: Path,
    ) {
        window(dir, 1_440, PERF_MINUTE_MS, 8)
    }

    @Test
    fun `a full week of retained hourly history returns every count and histogram`(@TempDir dir: Path) {
        window(dir, 168, PERF_HOUR_MS, 48)
    }

    private fun window(dir: Path, intervals: Int, resolution: Long, metrics: Int) {
        val start = PERF_DAY_MS
        val until = start + intervals * resolution
        assertTrue(seed(dir, intervals, resolution, metrics, start) > 1_024 * 1_024)
        PerfAggregateStore(dir, identity, WallClock { until }).use { store ->
            val read = store.read(start, until)
            try {
                assertTrue(read.coverage.issues.isEmpty(), read.coverage.issues.toString())
                assertEquals(until - start, read.coverage.observedMs)
                assertTrue(read.resolutionMs > resolution)
                assertTrue(Json.encodeToString(read).toByteArray().size < 4 * 1_024 * 1_024)
                val points = read.snapshots.flatMap { it.points }
                assertEquals(metrics, points.groupBy { it.metric }.size)
                for (metric in points.groupBy { it.metric }.values) {
                    assertEquals(intervals * 2L, metric.sumOf { it.count })
                    assertEquals(intervals * 30.0, metric.sumOf { it.sum })
                    assertEquals(intervals.toLong(), metric.sumOf { it.buckets[0] })
                    assertEquals(intervals.toLong(), metric.sumOf { it.buckets[1] })
                    assertEquals(10.0, metric.mapNotNull { it.min }.min())
                    assertEquals(20.0, metric.mapNotNull { it.max }.max())
                }
            } finally {
                PerfWorkBudget.release(read)
            }
        }
    }
    private fun seed(dir: Path, intervals: Int, resolution: Long, metrics: Int, start: Long): Long {
        var diskBytes = 0L
        repeat(intervals) { index ->
            val from = start + index * resolution
            val points = List(metrics) { metric ->
                PerfAggregatePoint(
                    "metric-$metric",
                    PerfScope(
                        head = "representative",
                        model = "example-model",
                    ),
                    from, from + resolution,
                    2L, 30.0, 10.0, 20.0, List(22) { if (it < 2) 1L else 0L },
                )
            }
            val snapshot =
                PerfAggregateSnapshot(
                    identity,
                    from,
                    from + resolution,
                    points,
                    PerfCoverage(from, from + resolution, resolution),
                )
            val encoded = Json.encodeToString(
                PerfHistoryFile(resolutionMs = resolution, fromMs = from, snapshots = listOf(snapshot)),
            )
            val name = if (resolution == PERF_MINUTE_MS) "minute" else "hour"
            Files.writeString(dir.resolve("$name-$from.json"), encoded)
            diskBytes += encoded.toByteArray().size
        }
        return diskBytes
    }
}
