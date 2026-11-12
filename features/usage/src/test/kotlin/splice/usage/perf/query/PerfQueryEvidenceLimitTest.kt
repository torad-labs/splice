package splice.usage.perf.query

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.UsageMeasurementStamp
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

class PerfQueryEvidenceLimitTest {
    @Test
    fun `irreducible dimension limit stays partial but does not stop known cohort totals`() {
        val events = (0 until 200).flatMap(::events)
        val sources = object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("test")
            override fun rows(head: String): PerfRowsSource? = null
            override fun evidence(): PerfEvidenceSource = PerfEvidenceSource {
                PerfEvidenceRead(events, PerfCoverage(0, 60_000, 60_000), 60_000, true)
            }
        }
        PerfQueryService(sources, clock = WallClock { 60_000L }).use { service ->
            val result = service.series(PerfQuery(0, 60_000, source = "evidence"))
            try {
                val counts = result.getValue("counts").jsonObject
                assertEquals("200", JsonScalars.str(counts, "known_arrivals"))
                assertEquals("200", JsonScalars.str(counts, "known_completions"))
                assertEquals("false", JsonScalars.str(counts, "population_complete"))
                assertEquals("partial", JsonScalars.str(result, "status"))
                assertTrue(result.toString().contains("request_point_limit"))
                val cells = result.getValue("series").jsonArray.sumOf {
                    it.jsonObject.getValue("metrics").jsonObject.size
                }
                assertTrue(cells <= 2_048)
            } finally {
                PerfWorkBudget.release(result)
            }
        }
    }

    private fun events(index: Int): List<PerfObservation> {
        val identity = PerfIdentity("boot", "build", "version", "config")
        val scope = PerfScope(
            head = "test",
            model = "model-$index",
            transport = "sse",
            streaming = true,
            local = false,
            compact = false,
        )
        val links = PerfLinks(requestId = "request-$index")
        val usage = mapOf(
            "input_tokens" to 15.0,
            "output_tokens" to 4.0,
            "cached_tokens" to 5.0,
            "usage_cache_write_tokens" to 0.0,
            "usage_cache_write_1h_tokens" to 0.0,
        ) + UsageMeasurementStamp.fields.keys.associateWith { 1.0 }
        return listOf(
            PerfObservation(
                PerfStamp("$index-a", identity, 100, 100),
                scope,
                PerfEventKind.REQUEST_ARRIVED,
                links = links,
            ),
            PerfObservation(
                PerfStamp("$index-z", identity, 120, 120),
                scope,
                PerfEventKind.REQUEST_FINISHED,
                mapOf("arrival_to_terminal_ms" to 20.0),
                links,
                "ok",
            ),
            PerfObservation(
                PerfStamp("$index-u", identity, 120, 120),
                scope,
                PerfEventKind.USAGE_RECORDED,
                usage,
                links,
            ),
        )
    }
}
