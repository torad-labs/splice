package splice.usage.perf.query

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.history.PerfEvidenceSource

class PerfMetricPopulationTest {
    @Test
    fun `complete request evidence does not lend its coverage to retained aggregate gap measurements`() {
        val identity = PerfIdentity("boot", "build", "v", "revision")
        val scope = PerfScope(head = "h")
        val terminal = PerfObservation(
            PerfStamp("finish", identity, 30_000, 30_000),
            scope,
            PerfEventKind.REQUEST_FINISHED,
            mapOf("arrival_to_terminal_ms" to 100.0),
            PerfLinks(requestId = "r"),
            OutcomeTag.OK.wire,
        )
        val service = PerfQueryService(
            object : PerfQuerySources {
                override fun heads(): Set<String> = setOf("h")
                override fun rows(head: String): PerfRowsSource? = null
                override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
                    identity,
                    0,
                    60_000,
                    listOf(PerfAggregatePoint("upstream_gap_ms", scope, 0, 60_000, 1, 400.0, 400.0, 400.0)),
                    PerfCoverage(0, 60_000, 0),
                )
                override fun evidence(): PerfEvidenceSource = PerfEvidenceSource {
                    PerfEvidenceRead(listOf(terminal), PerfCoverage(0, 60_000, 60_000), 60_000, true)
                }
            },
            clock = WallClock { 60_000 },
        )
        val result = service.summary(PerfQuery(0, 60_000))
        try {
            val histograms = result.getValue("histograms").jsonObject
            val terminalHistogram = histograms.getValue("arrival_to_terminal_ms").jsonObject
            val gaps = histograms.getValue("upstream_gap_ms").jsonObject
            assertEquals(
                "request_scope_resolved_at_terminal_or_latest_checkpoint",
                JsonScalars.str(terminalHistogram, "filter_attribution"),
            )
            assertEquals("true", JsonScalars.str(terminalHistogram.getValue("coverage").jsonObject, "complete"))
            assertEquals("aggregate_event_time_scope", JsonScalars.str(gaps, "filter_attribution"))
            assertEquals("false", JsonScalars.str(gaps.getValue("coverage").jsonObject, "complete"))
            assertEquals("0", JsonScalars.str(gaps.getValue("coverage").jsonObject, "observed_ms"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }
}
