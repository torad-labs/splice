package splice.observability.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfCoverage
import splice.core.util.PerfWorkBudget
import java.util.concurrent.atomic.AtomicReference

class ExportStatusOwnershipTest {
    @Test
    fun `held statuses keep their own budget and data across refresh and exporter close`() {
        val baseline = PerfWorkBudget.retainedBytes()
        val snapshot = AtomicReference(ExportFixtures.snapshot().copy(coverage = PerfCoverage(0, 60_000, 20_000)))
        val exports = PerfExports(
            PerfExportConfig(prometheus = true),
            ExportFixtures.identity,
            PerfAggregateSource { snapshot.get() },
        )
        val held = ArrayList<JsonObject>()
        try {
            assertTrue(exports.flush())
            val first = exports.status().also(held::add)
            snapshot.set(ExportFixtures.snapshot(5).copy(coverage = PerfCoverage(0, 60_000, 40_000)))
            assertTrue(exports.flush())
            val second = exports.status().also(held::add)
            exports.close()
            assertEquals(baseline + RESPONSE_BYTES * 2, PerfWorkBudget.retainedBytes())
            assertEquals(JsonPrimitive(20_000), source(first)["observed_ms"])
            assertEquals(JsonPrimitive(40_000), source(second)["observed_ms"])
            PerfWorkBudget.release(first)
            assertEquals(baseline + RESPONSE_BYTES, PerfWorkBudget.retainedBytes())
            assertEquals(JsonPrimitive(40_000), source(second)["observed_ms"])
            PerfWorkBudget.release(second)
            assertEquals(baseline, PerfWorkBudget.retainedBytes())
        } finally {
            exports.close()
            held.forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `exhausted response budget returns fixed busy data and recovers after explicit release`() {
        val baseline = PerfWorkBudget.retainedBytes()
        PerfExports(
            PerfExportConfig(),
            ExportFixtures.identity,
            PerfAggregateSource { ExportFixtures.snapshot() },
        ).use { exports ->
            ExportStatusRead(exports).use { held ->
                val pressure = requireNotNull(PerfWorkBudget.acquire(WORK_BYTES - PerfWorkBudget.retainedBytes()))
                pressure.use {
                    val busy = exports.status()
                    assertEquals(JsonPrimitive("busy"), busy["status"])
                    assertEquals(JsonPrimitive("shared_work_budget"), busy["reason"])
                    assertEquals(2, busy.size)
                    assertEquals(WORK_BYTES, PerfWorkBudget.retainedBytes())
                    assertFalse(held.coverage.getValue("sampled").jsonPrimitive.boolean)
                    PerfWorkBudget.release(busy)
                }
                ExportStatusRead(exports).use { resumed ->
                    assertTrue(resumed.value.containsKey("coverage"))
                    assertEquals(baseline + RESPONSE_BYTES * 2, PerfWorkBudget.retainedBytes())
                }
            }
        }
        assertEquals(baseline, PerfWorkBudget.retainedBytes())
    }

    private fun source(status: JsonObject): JsonObject = status.getValue(
        "coverage",
    ).jsonObject.getValue("source").jsonObject
}

private const val RESPONSE_BYTES = 96L * 1_024
private const val WORK_BYTES = 16L * 1_024 * 1_024
