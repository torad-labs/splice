package splice.observability.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicLong

class ExportCoverageTest {
    @Test
    fun `partial source with no issues retains interval observed duration and advancing age`() {
        val snapshot = ExportFixtures.snapshot(from = 1_000, until = 6_000).copy(
            coverage = PerfCoverage(1_000, 6_000, 2_000),
        )
        val now = AtomicLong(7_000L)
        PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { snapshot },
            WallClock { now.get() },
        ).use { exports ->
            assertTrue(exports.flush())
            ExportStatusRead(exports).use { status ->
                val coverage = status.coverage
                val source = coverage.getValue("source").jsonObject
                assertEquals(JsonPrimitive(1_000), source["requested_from_ms"])
                assertEquals(JsonPrimitive(6_000), source["requested_until_ms"])
                assertEquals(JsonPrimitive(2_000), source["observed_ms"])
                assertEquals(JsonPrimitive(true), source["partial"])
                assertEquals(JsonArray(emptyList()), source["issues"])
                assertEquals(JsonPrimitive(0), coverage["source_issues"])
                now.set(9_000)
                ExportStatusRead(exports).use { fresh ->
                    val sampled = fresh.coverage.getValue("sampled_epoch_ms").jsonPrimitive.long
                    assertEquals(JsonPrimitive(9_000 - sampled), fresh.coverage["sample_age_ms"])
                    assertEquals(JsonPrimitive(3_000), fresh.coverage["source_age_ms"])
                    assertEquals(coverage.getValue("source"), fresh.coverage["source"])
                }
                assertTrue(exports.prometheus().contains("splice_requests_finished_total"))
            }
        }
    }

    @Test
    fun `issue projection groups safe names and counts while bounding and omitting content`() {
        val issues = listOf(
            PerfIssue("observation_dropped", "accumulator", 2, "secret-detail"),
            PerfIssue("observation_dropped", "accumulator", 3),
            PerfIssue("secret-code", "secret-source", 4, "secret-content"),
        ) + List(70) { PerfIssue("snapshot_busy", "accumulator") }
        val snapshot = ExportFixtures.snapshot().copy(coverage = PerfCoverage(0, 60_000, 20_000, issues))
        PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { snapshot },
            WallClock { 60_000 },
        ).use { exports ->
            assertTrue(exports.flush())
            ExportStatusRead(exports).use { status ->
                val coverage = status.coverage
                val source = coverage.getValue("source").jsonObject
                val returned = source["issues"] as JsonArray
                val groups = returned.map { it.jsonObject }
                val dropped = groups.single { it["code"] == JsonPrimitive("observation_dropped") }
                assertEquals(JsonPrimitive("accumulator"), dropped["source"])
                assertEquals(JsonPrimitive(5), dropped["count"])
                assertEquals(JsonPrimitive(79), coverage["source_issues"])
                assertEquals(JsonPrimitive(9), source["issue_entries_omitted"])
                assertEquals(JsonPrimitive(1), source["issue_entries_normalized"])
                assertTrue(returned.any { it.jsonObject["source"] == JsonPrimitive("unknown") })
                assertFalse(coverage.toString().contains("secret"))
            }
        }
    }

    @Test
    fun `unsampled coverage stays explicit`() {
        PerfExports(
            PerfExportConfig(),
            ExportFixtures.identity,
            PerfAggregateSource { ExportFixtures.snapshot() },
            WallClock { 1_000 },
        ).use { exports ->
            assertTrue(exports.flush())
            ExportStatusRead(exports).use { status ->
                val coverage = status.coverage
                assertEquals(JsonPrimitive(false), coverage["sampled"])
                assertFalse(coverage.containsKey("source"))
            }
        }
    }
}
