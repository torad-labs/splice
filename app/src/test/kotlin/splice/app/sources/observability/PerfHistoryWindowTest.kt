package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistory
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
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

    @Test
    fun `dense valid hourly files remain readable under the smaller source envelope`(@TempDir dir: Path) {
        val from = PERF_DAY_MS
        val until = from + PERF_HOUR_MS
        val points = List(512) { index ->
            PerfAggregatePoint(
                "duration-${index % 2}",
                PerfScope(head = "representative", model = "model-${index / 2}-" + "x".repeat(64)),
                from, until, 2L, 30.0, 10.0, 20.0, List(22) { if (it < 2) 1L else 0L },
            )
        }
        val snapshot = PerfAggregateSnapshot(identity, from, until, points, PerfCoverage(from, until, PERF_HOUR_MS))
        val encoded = Json.encodeToString(
            PerfHistoryFile(resolutionMs = PERF_HOUR_MS, fromMs = from, snapshots = listOf(snapshot)),
        )
        assertTrue(encoded.toByteArray().size > 128 * 1_024)
        Files.writeString(dir.resolve("hour-$from.json"), encoded)
        checkNotNull(PerfWorkBudget.acquire(11L * 1_024 * 1_024 / 2)).use {
            PerfAggregateStore(dir, identity, WallClock { until }).use { store ->
                val read = store.read(from, until)
                try {
                    assertTrue(read.coverage.issues.isEmpty(), read.coverage.issues.toString())
                    assertEquals(1_024L, read.snapshots.flatMap { it.points }.sumOf { it.count })
                    assertEquals(512L, read.snapshots.flatMap { it.points }.sumOf { it.buckets[0] })
                } finally {
                    PerfWorkBudget.release(read)
                }
            }
        }
    }

    @Test
    fun `composed cold day and week reads fit ordinary owners after previous replies and cache`(@TempDir root: Path) {
        // Alerts 2.75 MiB + profiles 528 KiB + config/runtime/head metadata ~350 KiB,
        // plus ledger/export/config headroom: 5.5 MiB persist throughout both cold queries.
        checkNotNull(PerfWorkBudget.acquire(11L * 1_024 * 1_024 / 2)).use {
            val start = PERF_DAY_MS
            val day = Files.createDirectory(root.resolve("day"))
            val week = Files.createDirectory(root.resolve("week"))
            seed(day, 1_440, PERF_MINUTE_MS, 8, start)
            seed(week, 168, PERF_HOUR_MS, 48, start)
            val dayStore = PerfAggregateStore(day, identity, WallClock { start + PERF_DAY_MS })
            val weekStore = PerfAggregateStore(week, identity, WallClock { start + 7 * PERF_DAY_MS })
            var selected: PerfHistory = dayStore
            PerfQueryService(
                object : PerfQuerySources {
                    override fun heads(): Set<String> = setOf("representative")
                    override fun rows(head: String): PerfRowsSource? = null
                    override fun history(): PerfHistory = selected
                },
                clock = WallClock { start + 7 * PERF_DAY_MS },
            ).use { service ->
                val first = service.series(PerfQuery(start, start + PERF_DAY_MS, source = "history"))
                try { assertSeriesCount(first, 1_440L * 8 * 2) } finally { PerfWorkBudget.release(first) }
                selected = weekStore
                val second = service.series(PerfQuery(start, start + 7 * PERF_DAY_MS, source = "history"))
                try { assertSeriesCount(second, 168L * 48 * 2) } finally { PerfWorkBudget.release(second) }
            }
            dayStore.close()
            weekStore.close()
        }
    }

    private fun assertSeriesCount(value: kotlinx.serialization.json.JsonObject, expected: Long) {
        assertTrue(!value.toString().contains("memory_busy"), value.toString())
        val count = value.getValue("series").jsonArray.sumOf { row ->
            row.jsonObject.getValue("metrics").jsonObject.values.sumOf { metric ->
                checkNotNull(JsonScalars.str(metric.jsonObject, "count")).toLong()
            }
        }
        assertEquals(expected, count)
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
