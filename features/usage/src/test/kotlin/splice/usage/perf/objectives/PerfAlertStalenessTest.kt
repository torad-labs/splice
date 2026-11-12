package splice.usage.perf.objectives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path

class PerfAlertStalenessTest {
    @Test
    fun `instant snapshot coverage must be explicitly disabled for staleness rules`(@TempDir tmp: Path) {
        PerfObjectiveStore(tmp.resolve("objectives.json")).use { store ->
            val settings = Json.parseToJsonElement(objectiveSettings(metric = "telemetry.staleness_ms")).jsonObject
            val result = takeReply(store.update(settings))
            assertEquals("error", result.text("status"))
            assertEquals("instant_snapshot_requires_zero_min_coverage", result.obj("error").text("code"))
            assertEquals("[]", takeReply(store.settings())["rules"].toString())
        }
    }

    @Test
    fun `known current sink samples can sustain staleness without claiming any interval coverage`(@TempDir tmp: Path) {
        PerfObjectiveStore(tmp.resolve("objectives.json")).use { store ->
            val settings = objectiveSettings(metric = "telemetry.staleness_ms")
                .replace("\"min_coverage\":0.9", "\"min_coverage\":0")
            assertEquals("ok", takeReply(store.update(Json.parseToJsonElement(settings).jsonObject)).text("status"))
            val source = SinkSnapshots()
            val query = PerfQueryService(source, clock = WallClock { source.now })
            PerfAlertEngine(query, store, PerfAlertDelivery { false }).use { engine ->
                val pending = sample(engine, source, 120_000)
                assertEquals("pending", pending.text("state"))
                assertEquals(30_000.0, pending.number("observed"))
                assertEquals(2L, pending.long("sample_count"))
                assertEquals(0.0, pending.obj("coverage").number("fraction"))
                assertEquals(0.0, pending.obj("coverage").number("observed_ms"))
                assertEquals("false", pending.obj("coverage").text("complete"))
                assertEquals("current_sink_snapshot", pending.obj("coverage").text("temporal_basis"))
                assertEquals(120_000L, pending.obj("coverage").long("sampled_at_ms"))
                assertEquals("current_sink_snapshot", pending.text("window_basis"))
                assertEquals(120_000L, pending.long("data_as_of_ms"))
                assertEquals("null", pending["settled_until_ms"].toString())
                assertEquals("firing", sample(engine, source, 130_000).text("state"))
                source.secondSuccess = null
                val missing = sample(engine, source, 140_000)
                assertEquals("insufficient_data", missing.text("state"))
                assertEquals("sink_freshness_unknown", missing.text("reason"))
                assertEquals("true", missing.text("active"))
                source.firstSuccess = 150_000
                source.secondSuccess = 150_000
                assertEquals("firing", sample(engine, source, 150_000).text("state"))
                source.firstSuccess = 160_000
                source.secondSuccess = 160_000
                assertEquals("resolved", sample(engine, source, 160_000).text("state"))
            }
        }
    }

    private fun sample(engine: PerfAlertEngine, source: SinkSnapshots, at: Long): JsonObject {
        source.now = at
        return takeReply(engine.evaluate(at)).event()
    }

    private class SinkSnapshots : PerfQuerySources {
        var now = 120_000L
        var firstSuccess = 90_000L
        var secondSuccess: Long? = 90_000L
        override fun heads(): Set<String> = setOf("h")
        override fun rows(head: String): PerfRowsSource? = null
        override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
            PerfIdentity("boot", "build", "version", "config"),
            0,
            now,
            emptyList(),
            PerfCoverage(0, now, 0, sinks = listOf(sink("one", firstSuccess), sink("two", secondSuccess))),
        )
        private fun sink(name: String, success: Long?): PerfSinkStatus =
            PerfSinkStatus(name, 2, 2, 2, 0, 0, 0, null, success)
    }
}
