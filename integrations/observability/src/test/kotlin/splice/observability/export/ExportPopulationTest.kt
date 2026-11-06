package splice.observability.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry

class ExportPopulationTest {
    @Test
    fun `minute replacement and expiry preserve cumulative population without scrape mutation`() {
        ExportTotals().use { totals ->
            val first = ExportFixtures.snapshot()
            totals.reconcile(first)
            totals.reconcile(first)
            assertEquals(3.0, totals.points().single().sum)
            totals.reconcile(ExportFixtures.snapshot(5))
            assertEquals(5.0, totals.points().single().sum)
            totals.reconcile(ExportFixtures.snapshot(2, 60_000, 120_000))
            assertEquals(7.0, totals.points().single().sum)
            assertEquals(7L, totals.points().single().count)
            val text = PrometheusText.render(totals.points(), totals.dropped, totals.missingMs)
            assertEquals(text, PrometheusText.render(totals.points(), totals.dropped, totals.missingMs))
            assertTrue(text.contains("splice_requests_finished_total{"))
            assertEquals(7.0, OtlpMetricData.of(totals.points()).single().doubleSumData.points.single().value)
            totals.reconcile(ExportFixtures.snapshot(1, 240_000, 300_000))
            assertEquals(120_000L, totals.missingMs)
            assertEquals(8.0, totals.points().single().sum)
        }
    }

    @Test
    fun `seconds histogram retains exact count sum and disjoint buckets`() {
        val bounds = PerfMetricRegistry.durationBoundsMs()
        val buckets = MutableList(bounds.size + 1) { 0L }
        buckets[bounds.indexOf(250.0)] = 2
        buckets[bounds.indexOf(1_000.0)] = 1
        val point = PerfAggregatePoint(
            "arrival_to_terminal_ms", ExportFixtures.scope, 0, 60_000, 3, 1_500.0, 250.0, 1_000.0, buckets,
        )
        val data = OtlpMetricData.of(listOf(point)).single()
        val exported = data.histogramData.points.single()
        assertEquals("splice_arrival_to_terminal_seconds", data.name)
        assertEquals("s", data.unit)
        assertEquals(3L, exported.count)
        assertEquals(1.5, exported.sum)
        assertEquals(buckets, exported.counts)
        assertEquals(bounds.map { it / 1_000 }, exported.boundaries)
        val text = PrometheusText.render(listOf(point), 0, 0)
        assertTrue(text.contains("le=\"+Inf\"} 3.0"))
        assertTrue(text.contains("_sum{head=\"test\""))
    }

    @Test
    fun `label pressure drops explicitly and never exports identifiers or URLs`() {
        ExportTotals().use { totals ->
            val sample = ExportFixtures.snapshot()
            val points = (1..300).map {
                sample.points.single().copy(
                    scope = ExportFixtures.scope.copy(model = "model-$it"),
                )
            }
            totals.reconcile(sample.copy(points = points))
            assertEquals(256, totals.points().size)
            assertEquals(44L, totals.dropped)
            val bad = sample.points.single().copy(
                scope = ExportFixtures.scope.copy(
                    head = "https://secret.invalid",
                    model = "deadbeefdeadbeefdeadbeefdeadbeef",
                ),
            )
            val text = PrometheusText.render(listOf(bad.copy(scope = ExportSchema.scope(bad.scope))), 0, 0)
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("deadbeef"))
        }
    }
}
