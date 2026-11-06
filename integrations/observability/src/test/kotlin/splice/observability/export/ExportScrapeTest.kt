package splice.observability.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicReference

class ExportScrapeTest {
    @Test
    fun `large labels and series either fit entirely or return explicit oversize`() {
        val source = AtomicReference(ScrapeFixtures.snapshot(histogram = false))
        PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { source.get() },
        ).use { exports ->
            assertTrue(exports.flush())
            exports.scrape().use { scrape ->
                assertTrue(scrape.available)
                assertTrue(scrape.text.toByteArray(Charsets.UTF_8).size <= PerfScrapeLimits.MAX_TEXT_BYTES)
                assertEquals(256, scrape.text.lineSequence().count { it.startsWith("splice_requests_finished_total{") })
            }
            source.set(ScrapeFixtures.snapshot(histogram = true))
            assertFalse(exports.flush())
            exports.scrape().use { scrape ->
                assertFalse(scrape.available)
                assertEquals("oversize", scrape.state)
                assertTrue(scrape.text.contains("unavailable: oversize"))
                assertFalse(scrape.text.contains("splice_requests_finished_total"))
            }
            ExportStatusRead(exports).use { status ->
                val scrape = status.value.getValue("prometheus").jsonObject
                assertEquals(JsonPrimitive("oversize"), scrape["state"])
                assertTrue(scrape.getValue("render_oversize").jsonPrimitive.long > 0)
                assertTrue(scrape.containsKey("last_success_epoch_ms"))
                assertTrue(scrape.containsKey("sample_age_ms"))
            }
        }
    }

    @Test
    fun `unsampled stale and closed snapshots never report available`() {
        var now = 1_000L
        ExportTotals().use { totals ->
            PrometheusScrapes(true, WallClock { now }).use { scrapes ->
                scrapes.scrape().use { assertEquals("unsampled", it.state) }
                totals.reconcile(ExportFixtures.snapshot())
                assertTrue(scrapes.publish(totals.points(), totals))
                now += PerfScrapeLimits.MAX_AGE_MS + 1
                scrapes.scrape().use { stale ->
                    assertFalse(stale.available)
                    assertEquals("stale", stale.state)
                    assertTrue(stale.text.contains("unavailable"))
                }
                assertEquals(JsonPrimitive(PerfScrapeLimits.MAX_AGE_MS + 1), scrapes.status()["sample_age_ms"])
                scrapes.close()
                scrapes.scrape().use { assertEquals("closed", it.state) }
            }
        }
    }
}

internal object ScrapeFixtures {
    fun snapshot(histogram: Boolean): PerfAggregateSnapshot {
        val original = ExportFixtures.snapshot()
        val bounds = PerfMetricRegistry.durationBoundsMs()
        val buckets = List(bounds.size + 1) { if (it == 0) 1L else 0L }
        val points = (0 until 256).map { index ->
            val scope = ExportFixtures.scope.copy(head = "h".repeat(96), model = "model-$index-" + "m".repeat(80))
            if (histogram) {
                PerfAggregatePoint(
                    "arrival_to_terminal_ms", scope, 0, 60_000, 1, bounds.first(), 0.0, bounds.first(), buckets,
                )
            } else {
                original.points.single().copy(scope = scope)
            }
        }
        return original.copy(points = points)
    }
}
