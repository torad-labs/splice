package splice.app.sources.observability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.instrumentation.WorkBudgetMark
import splice.core.perf.UsageMeasurementStamp
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path

class PerfEvidenceRetainedWindowTest {
    @Test
    fun `full day and week summaries and series preserve production-written request populations`(@TempDir root: Path) {
        val budget = WorkBudgetMark()
        checkNotNull(PerfWorkBudget.acquire(5L * 1_024 * 1_024)).use {
            RetainedEvidenceFixture(root).use { fixture ->
                fixture.seed()
                windows(fixture)
            }
        }
        budget.assertGivenBack()
    }

    private fun windows(fixture: RetainedEvidenceFixture) {
        fixture.service().use { service ->
            listOf(1_440, 10_080).forEach { minutes ->
                val query = fixture.query(minutes)
                val summary = service.summary(query)
                try { summaryPopulation(summary, minutes) } finally { PerfWorkBudget.release(summary) }
                val returnedSeries = service.series(query)
                try { series(returnedSeries, minutes) } finally { PerfWorkBudget.release(returnedSeries) }
            }
        }
    }

    private fun summaryPopulation(value: JsonObject, count: Int) {
        population(value, count)
        val workload = value.getValue("comparison").jsonObject.getValue("workload").jsonObject
        assertEquals("true", JsonScalars.str(workload, "population_complete"))
    }

    private fun population(value: JsonObject, count: Int) {
        val counts = value.getValue("counts").jsonObject
        assertEquals(count.toString(), JsonScalars.str(counts, "arrivals"), value.toString())
        assertEquals(count.toString(), JsonScalars.str(counts, "completions"))
        assertEquals(count.toString(), JsonScalars.str(counts, "known_successful_completions"))
        assertEquals("true", JsonScalars.str(counts, "population_complete"))
        val coverage = value.getValue("coverage").jsonObject
        assertEquals("true", JsonScalars.str(coverage, "complete"), value.toString())
        assertEquals((count * 60_000L).toString(), JsonScalars.str(coverage, "observed_ms"))
        val requestCoverage = value.getValue("population_coverage").jsonObject
            .getValue("request_scope_resolved_at_terminal_or_latest_checkpoint").jsonObject
        assertEquals("true", JsonScalars.str(requestCoverage, "complete"))
        assertEquals((count * 60_000L).toString(), JsonScalars.str(requestCoverage, "observed_ms"))
        assertFalse(value.toString().contains("request_point_limit"), value.toString())
        assertTrue(checkNotNull(JsonScalars.str(value, "resolution_ms")).toLong() > 60_000L)
    }

    private fun series(value: JsonObject, count: Int) {
        population(value, count)
        val rows = value.getValue("series").jsonArray
        val arrivals = rows.sumOf { row ->
            val metric = row.jsonObject.getValue("metrics").jsonObject["requests_arrived"]?.jsonObject
            metric?.let { checkNotNull(JsonScalars.str(it, "sum")).toDouble() } ?: 0.0
        }
        assertEquals(count.toDouble(), arrivals)
        val histograms = rows.mapNotNull {
            it.jsonObject.getValue("metrics").jsonObject["arrival_to_terminal_ms"]?.jsonObject
        }
        assertEquals(count.toLong(), histograms.sumOf { checkNotNull(JsonScalars.str(it, "count")).toLong() })
        assertEquals(count * 20.0, histograms.sumOf { checkNotNull(JsonScalars.str(it, "sum")).toDouble() })
        assertEquals(
            count.toLong(),
            histograms.sumOf { histogram -> histogram.getValue("buckets").jsonArray.sumOf { it.jsonPrimitive.long } },
        )
        assertTrue(PerfWorkBudget.retainedBytes() <= PerfWorkBudget.LIMIT_BYTES)
    }
}

private class RetainedEvidenceFixture(private val directory: Path) : AutoCloseable, PerfQuerySources {
    private var now = 60_000L
    private val clock = WallClock { now }
    private val writer = RequestEvidenceFiles(directory, clock = clock, elapsed = ElapsedClock { now })
    private val head = writer.forHead("test")
    private val source = PerfEvidenceFileSource(directory, clock)
    private val identity = PerfIdentity("boot", "build", "version", "config")
    private val scope = PerfScope(
        head = "test",
        model = "model",
        transport = "sse",
        local = false,
        compact = false,
        streaming = true,
    )

    fun seed() {
        val started = MonoClock.nowMs()
        assertTrue(AsyncFileIo.drain()) { failure("before_seed", null, started) }
        assertTrue(writer.startCoverage("test", identity.bootId)) { failure("start_coverage", null, started) }
        repeat(10_080) { index ->
            val from = now
            record(index, PerfEventKind.REQUEST_ARRIVED)
            now += 20
            record(index, PerfEventKind.REQUEST_FINISHED)
            record(index, PerfEventKind.USAGE_RECORDED)
            now = from + 60_000L
            if ((index + 1) % 60 == 0) checkpoint(index)
        }
        val stopping = MonoClock.nowMs()
        assertTrue(writer.stopCoverage("test")) { failure("stop_coverage", null, stopping) }
        settle("final_coverage", null)
    }

    private fun record(index: Int, kind: PerfEventKind) {
        val started = MonoClock.nowMs()
        assertTrue(head.record(event(index, kind))) { failure("record_$kind", index, started) }
        // The queued writer reads this virtual clock; settle its write before the next clock advance.
        settle("seed_$kind", index)
    }

    private fun checkpoint(index: Int) {
        settle("before_coverage", index)
        val started = MonoClock.nowMs()
        assertTrue(writer.checkpointCoverage()) { failure("checkpoint_coverage", index, started) }
        settle("after_coverage", index)
    }

    private fun settle(stage: String, index: Int?) {
        val started = MonoClock.nowMs()
        assertTrue(AsyncFileIo.awaitDirectory(directory)) { failure(stage, index, started) }
    }

    private fun failure(stage: String, index: Int?, started: Long): String =
        "stage=$stage request_index=$index barrier_elapsed_ms=${MonoClock.nowMs() - started} " +
            "interrupted=${Thread.currentThread().isInterrupted} evidence=${head.sink.status()} " +
            "coverage=${writer.coverageSink.status()} io=${AsyncFileIo.workSnapshot} " +
            "work_budget=${PerfWorkBudget.snapshot()}"

    private fun event(index: Int, kind: PerfEventKind): PerfObservation {
        val usage = mapOf(
            "input_tokens" to 15.0, "output_tokens" to 4.0, "cached_tokens" to 5.0,
            "usage_cache_write_tokens" to 0.0, "usage_cache_write_1h_tokens" to 0.0,
            "missing_usage" to 0.0, "usage_absorbed_rounds" to 0.0, "usage_cut_rounds" to 0.0,
            "usage_no_request" to 0.0,
        ) + UsageMeasurementStamp.fields.keys.associateWith { 1.0 }
        val values = when (kind) {
            PerfEventKind.USAGE_RECORDED -> usage
            PerfEventKind.REQUEST_FINISHED -> mapOf("arrival_to_terminal_ms" to 20.0)
            PerfEventKind.REQUEST_ARRIVED, PerfEventKind.REQUEST_ADMITTED,
            PerfEventKind.ATTEMPT_STARTED, PerfEventKind.ATTEMPT_FINISHED,
            PerfEventKind.GATE_STATE, PerfEventKind.CLIENT_PROGRESS, PerfEventKind.GAP,
            PerfEventKind.PROTOCOL_PROGRESS, PerfEventKind.TOOL_STARTED, PerfEventKind.CONTENT_PROGRESS,
            PerfEventKind.TOOL_PHASE, PerfEventKind.CHECKPOINT, PerfEventKind.TOOL_FINISHED,
            PerfEventKind.RUNTIME, PerfEventKind.SELF_COST,
            -> emptyMap()
        }
        return PerfObservation(
            PerfStamp("$index-${kind.name}", identity, now, now),
            scope,
            kind,
            values,
            PerfLinks(requestId = "request-$index"),
            if (kind == PerfEventKind.REQUEST_FINISHED) "ok" else null,
        )
    }

    fun query(minutes: Int): PerfQuery =
        PerfQuery(now - minutes * 60_000L, now, mapOf("head" to "test"), source = "evidence")
    fun service(): PerfQueryService = PerfQueryService(this, clock = clock)
    override fun heads(): Set<String> = setOf("test")
    override fun rows(head: String): PerfRowsSource? = null
    override fun evidence(): PerfEvidenceFileSource = source
    override fun close(): Unit = writer.close()
}
