package splice.observability.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.util.PerfWorkBudget
import java.util.concurrent.atomic.AtomicReference

class ExportScrapeOwnershipTest {
    @Test
    fun `held scrape text stays charged and unchanged through refresh and close`() {
        val baseline = PerfWorkBudget.retainedBytes()
        val source = AtomicReference(ExportFixtures.snapshot())
        val exports = PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { source.get() },
        )
        val held = ArrayList<PerfScrape>()
        try {
            assertTrue(exports.flush())
            val first = exports.scrape().also(held::add)
            source.set(ExportFixtures.snapshot(5))
            assertTrue(exports.flush())
            val second = exports.scrape().also(held::add)
            exports.close()
            assertEquals(baseline + 2 * PerfScrapeLimits.RESPONSE_BYTES, PerfWorkBudget.retainedBytes())
            assertTrue(first.available)
            assertTrue(counter(first.text).endsWith(" 3.0"))
            assertTrue(counter(second.text).endsWith(" 5.0"))
            first.close()
            assertEquals(baseline + PerfScrapeLimits.RESPONSE_BYTES, PerfWorkBudget.retainedBytes())
            assertTrue(counter(second.text).endsWith(" 5.0"))
            second.close()
            assertEquals(baseline, PerfWorkBudget.retainedBytes())
        } finally {
            exports.close()
            held.forEach(PerfScrape::close)
        }
    }

    @Test
    fun `budget refusal is unavailable and releasing pressure restores a complete scrape`() {
        val baseline = PerfWorkBudget.retainedBytes()
        PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { ExportFixtures.snapshot() },
        ).use { exports ->
            assertTrue(exports.flush())
            exports.scrape().use { held ->
                requireNotNull(PerfWorkBudget.acquire(WORK_BYTES - PerfWorkBudget.retainedBytes())).use {
                    assertBusy(exports)
                    assertFalse(exports.flush())
                    assertEquals(WORK_BYTES, PerfWorkBudget.retainedBytes())
                    assertTrue(counter(held.text).endsWith(" 3.0"))
                }
            }
            assertTrue(exports.flush())
            val legacy = exports.prometheus()
            try {
                assertTrue(counter(legacy).endsWith(" 3.0"))
                assertTrue(legacy.toByteArray(Charsets.UTF_8).size <= PerfScrapeLimits.MAX_TEXT_BYTES)
            } finally {
                PerfWorkBudget.release(legacy)
            }
        }
        assertEquals(baseline, PerfWorkBudget.retainedBytes())
    }

    private fun counter(text: String): String = text.lineSequence().single {
        it.startsWith("splice_requests_finished_total{")
    }

    private fun assertBusy(exports: PerfExports) {
        exports.scrape().use { busy ->
            assertFalse(busy.available)
            assertEquals("busy", busy.state)
            assertTrue(busy.text.contains("unavailable: busy"))
        }
    }
}

private const val WORK_BYTES = 16L * 1_024 * 1_024
