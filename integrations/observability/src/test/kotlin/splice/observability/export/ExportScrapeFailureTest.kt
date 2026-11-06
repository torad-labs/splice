package splice.observability.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.util.WallClock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class ExportScrapeFailureTest {
    @Test
    fun `source failure immediately invalidates a fresh scrape and success restores it`() {
        val failure = AtomicReference<IllegalStateException?>()
        val source = PerfAggregateSource {
            failure.get()?.let { throw it }
            ExportFixtures.snapshot()
        }
        PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            source,
            WallClock { 1_000 },
        ).use { exports ->
            assertTrue(exports.flush())
            exports.scrape().use { assertTrue(it.available) }
            failure.set(IllegalStateException("source unavailable"))
            assertFalse(exports.flush())
            exports.scrape().use { scrape ->
                assertFalse(scrape.available)
                assertEquals("source_failed", scrape.state)
                assertEquals("# splice Prometheus scrape unavailable: source_failed\n", scrape.text)
            }
            ExportStatusRead(exports).use { status ->
                val scrape = status.value.getValue("prometheus").jsonObject
                assertEquals(JsonPrimitive("source_failed"), scrape["state"])
                assertEquals(JsonPrimitive(1_000), scrape["last_success_epoch_ms"])
                assertEquals(JsonPrimitive(1_000), scrape["last_attempt_epoch_ms"])
                assertTrue(status.value.getValue("worker_failures").jsonPrimitive.long > 0)
            }
            failure.set(null)
            assertTrue(exports.flush())
            exports.scrape().use { scrape ->
                assertTrue(scrape.available)
                assertTrue(scrape.text.contains("splice_requests_finished_total"))
            }
        }
    }

    @Test
    fun `snapshot cancellation and fatal failure propagate after invalidating the scrape`() {
        val failure = AtomicReference<Throwable?>()
        val source = PerfAggregateSource {
            failure.get()?.let { throw it }
            ExportFixtures.snapshot()
        }
        ExportEngine(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            source,
            ExportQueue(),
            WallClock { 1_000 },
        ).use { engine ->
            listOf(CancellationException("cancelled"), LinkageError("fatal")).forEach { expected ->
                failure.set(null)
                assertTrue(engine.tick())
                engine.scrape().use { assertTrue(it.available) }
                failure.set(expected)
                assertSame(expected, assertThrows(expected.javaClass) { engine.tick() })
                engine.scrape().use { scrape ->
                    assertFalse(scrape.available)
                    assertEquals("source_failed", scrape.state)
                }
            }
        }
    }
}
