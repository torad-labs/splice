package splice.usage.perf.objectives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path

class PerfAlertGaugeCoverageTest {
    @Test
    fun `aggregate gauge alerts remain evaluable when request collection horizon is unavailable`(@TempDir tmp: Path) {
        PerfObjectiveStore(tmp.resolve("objectives.json")).use { store ->
            val config = objectiveSettings(metric = "gauges.gate_queued.max", hold = 0)
            assertEquals("ok", takeReply(store.update(Json.parseToJsonElement(config).jsonObject)).text("status"))
            val query = PerfQueryService(CurrentGauge(), clock = WallClock { 120_000 })
            PerfAlertEngine(query, store, PerfAlertDelivery { false }).use { engine ->
                val event = takeReply(engine.evaluate(120_000)).event()
                assertEquals("firing", event.text("state"))
                assertEquals(200.0, event.number("observed"))
                assertEquals("current_aggregate_window", event.text("window_basis"))
                assertEquals(120_000L, event.obj("window").long("until_ms"))
                assertEquals(0L, event.long("window_lag_ms"))
                assertEquals("null", event["settled_until_ms"].toString())
            }
        }
    }

    @Test
    fun `two half covered gauge scopes never combine into one fully covered alert population`(@TempDir tmp: Path) {
        PerfObjectiveStore(tmp.resolve("objectives.json")).use { store ->
            val config = objectiveSettings(metric = "gauges.gate_queued.max", hold = 0)
            assertEquals("ok", takeReply(store.update(Json.parseToJsonElement(config).jsonObject)).text("status"))
            val query = PerfQueryService(
                object : PerfQuerySources {
                    override fun heads(): Set<String> = setOf("h")
                    override fun rows(head: String): PerfRowsSource? = null
                    override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
                        PerfIdentity("boot", "build", "v", "config"),
                        0,
                        60_000,
                        listOf(point("one"), point("two")),
                        PerfCoverage(0, 60_000, 60_000),
                    )
                },
                clock = WallClock { 60_000 },
            )
            PerfAlertEngine(query, store, PerfAlertDelivery { false }).use { engine ->
                val event = takeReply(engine.evaluate(60_000)).event()
                assertEquals(200.0, event.number("observed"))
                assertEquals(2L, event.long("sample_count"))
                assertEquals(0.5, event.obj("coverage").number("fraction"))
                assertEquals("insufficient_data", event.text("state"))
                assertEquals("minimum_coverage", event.text("reason"))
            }
        }
    }

    private fun point(model: String): PerfAggregatePoint = PerfAggregatePoint(
        "gate_queued", PerfScope(head = "h", model = model), 0, 60_000, 1, 200.0, 200.0, 200.0,
        last = 200.0, weightedSum = 6_000_000.0, coveredMs = 30_000,
    )

    private class CurrentGauge : PerfQuerySources {
        override fun heads(): Set<String> = setOf("h")
        override fun rows(head: String): PerfRowsSource? = null
        override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
            PerfIdentity("boot", "build", "v", "config"),
            60_000,
            120_000,
            listOf(
                PerfAggregatePoint(
                    "gate_queued", PerfScope(head = "h"), 60_000, 120_000, 2, 400.0, 200.0, 200.0,
                    last = 200.0, weightedSum = 12_000_000.0, coveredMs = 60_000,
                ),
            ),
            PerfCoverage(60_000, 120_000, 60_000),
        )
    }
}
