package splice.usage.perf.query

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRowsSource

class PerfRuleMetricsTest {
    @Test
    fun `counter rates use wall duration and cumulative gauge samples never become delta rates`() {
        val points = listOf(
            PerfAggregatePoint("requests_arrived", PerfScope(head = "a"), 0, 60_000, 3, 3.0, 1.0, 1.0),
            gauge("a", 2, 10.0),
            gauge("b", 3, 20.0),
        )
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("a", "b")
            override fun rows(head: String): PerfRowsSource? = null
            override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
                PerfIdentity("boot", "build", "1", "rev"),
                0,
                120_000,
                points,
                PerfCoverage(0, 120_000, 120_000),
            )
        })
        val result = service.summary(PerfQuery(0, 120_000, source = "live"))
        val counters = result.getValue("counters").jsonObject
        val arrivals = counters.getValue("requests_arrived").jsonObject
        assertEquals("3", JsonScalars.str(arrivals, "count"))
        assertEquals("3.0", JsonScalars.str(arrivals, "sum"))
        assertEquals("0.025", JsonScalars.str(arrivals, "per_s"))
        assertFalse("runtime_gc_count" in counters)
        val gauge = result.getValue("gauges").jsonObject.getValue("runtime_gc_count").jsonObject
        assertEquals("5", JsonScalars.str(gauge, "sample_count"))
        assertEquals("2", JsonScalars.str(gauge, "scope_count"))
        assertEquals("sum_over_observed_scopes", JsonScalars.str(gauge, "covered_ms_semantics"))
        assertEquals("120000", JsonScalars.str(gauge, "covered_ms"))
        assertEquals(JsonNull, gauge["per_s"])
        assertEquals("unsupported_for_sampled_gauge", JsonScalars.str(gauge, "rate_status"))
        PerfWorkBudget.release(result)
    }

    private fun gauge(head: String, samples: Long, last: Double): PerfAggregatePoint = PerfAggregatePoint(
        "runtime_gc_count", PerfScope(head = head), 0, 60_000, samples, last * samples, last, last,
        last = last, weightedSum = last * 60_000, coveredMs = 60_000,
    )
}
