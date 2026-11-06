package splice.observability.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.util.WallClock

class ExportCoverageTest {
    @Test
    fun `partial source with no issues retains interval observed duration and advancing age`() {
        val snapshot = ExportFixtures.snapshot(from = 1_000, until = 6_000).copy(
            coverage = PerfCoverage(1_000, 6_000, 2_000),
        )
        var now = 7_000L
        ExportEngine(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { snapshot },
            ExportQueue(),
            WallClock { now },
        ).use { engine ->
            assertTrue(engine.tick())
            val coverage = engine.coverage()
            val source = coverage.getValue("source").jsonObject
            assertEquals(JsonPrimitive(1_000), source["requested_from_ms"])
            assertEquals(JsonPrimitive(6_000), source["requested_until_ms"])
            assertEquals(JsonPrimitive(2_000), source["observed_ms"])
            assertEquals(JsonPrimitive(true), source["partial"])
            assertEquals(JsonArray(emptyList()), source["issues"])
            assertEquals(JsonPrimitive(0), coverage["source_issues"])
            now = 9_000
            assertEquals(JsonPrimitive(2_000), engine.coverage()["sample_age_ms"])
            assertEquals(JsonPrimitive(3_000), engine.coverage()["source_age_ms"])
            assertEquals(coverage.getValue("source"), engine.coverage()["source"])
            assertTrue(engine.prometheus().contains("splice_requests_finished_total"))
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
        ExportEngine(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { snapshot },
            ExportQueue(),
            WallClock { 60_000 },
        ).use { engine ->
            assertTrue(engine.tick())
            val coverage = engine.coverage()
            val source = coverage.getValue("source").jsonObject
            val returned = source["issues"] as JsonArray
            val dropped = returned.map { it.jsonObject }.single { it["code"] == JsonPrimitive("observation_dropped") }
            assertEquals(JsonPrimitive("accumulator"), dropped["source"])
            assertEquals(JsonPrimitive(5), dropped["count"])
            assertEquals(JsonPrimitive(79), coverage["source_issues"])
            assertEquals(JsonPrimitive(9), source["issue_entries_omitted"])
            assertEquals(JsonPrimitive(1), source["issue_entries_normalized"])
            assertTrue(returned.any { it.jsonObject["source"] == JsonPrimitive("unknown") })
            assertFalse(coverage.toString().contains("secret"))
        }
    }

    @Test
    fun `unsampled coverage stays explicit`() {
        ExportEngine(
            PerfExportConfig(),
            ExportFixtures.identity,
            PerfAggregateSource { ExportFixtures.snapshot() },
            ExportQueue(),
            WallClock { 1_000 },
        ).use { engine ->
            assertTrue(engine.tick())
            val coverage: JsonObject = engine.coverage()
            assertEquals(JsonPrimitive(false), coverage["sampled"])
            assertFalse(coverage.containsKey("source"))
        }
    }
}
