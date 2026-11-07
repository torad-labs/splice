package splice.app.sources.observability

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.UsageMeasurementStamp
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObjectiveSet
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
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfObservationConsumer
import splice.usage.perf.query.PerfConfigComparisonScope
import splice.usage.perf.query.PerfConfigInterval
import splice.usage.perf.query.PerfConfigProvenance
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path

class PerfEvidenceStreamingTest {
    @Test
    fun `three thousand five minute requests have exact goodput with real prefixes and persistent owners`(
        @TempDir root: Path,
    ) {
        // Five MiB represents ordinary owners; the actual writer contributes its persistent 512 KiB ledger.
        checkNotNull(PerfWorkBudget.acquire(5L * 1_024 * 1_024)).use {
            StreamingEvidenceFixture(root).use { fixture ->
                fixture.seed(3_000)
                val result = fixture.service().use { it.summary(fixture.query()) }
                try {
                    assertEquals(
                        "3000",
                        JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"),
                        { "$result work_budget=${PerfWorkBudget.snapshot()}" },
                    )
                    assertEquals("3000", JsonScalars.str(result.getValue("counts").jsonObject, "completions"))
                    val goodput = result.getValue("goodput").jsonObject
                    assertEquals("3000", JsonScalars.str(goodput, "good"))
                    assertEquals("10.0", JsonScalars.str(goodput, "per_s"))
                    assertEquals("true", JsonScalars.str(goodput, "population_complete"))
                    assertEquals("10.0", JsonScalars.str(result.getValue("rates").jsonObject, "attempts_per_s"))
                    assertEquals("ready", JsonScalars.str(result.getValue("comparison").jsonObject, "status"))
                    assertEquals("local", JsonScalars.str(result, "collection_mode"))
                    assertEquals("300000", JsonScalars.str(result, "requested_duration_ms"))
                    assertTrue(PerfWorkBudget.retainedBytes() <= PerfWorkBudget.LIMIT_BYTES)
                } finally {
                    PerfWorkBudget.release(result)
                }
            }
        }
    }

    @Test
    fun `consumer stop and conflicting identities remain incomplete after real streaming reads`(@TempDir root: Path) {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(2)
            val source = fixture.evidence()
            source.consume(fixture.evidenceQuery(), PerfObservationConsumer { false }).use {
                assertFalse(it.complete)
                assertEquals(0L, it.coverage.observedMs)
                assertTrue(it.coverage.issues.any { issue -> issue.code == "evidence_consumer_limit" })
            }
            fixture.conflict()
            val result = fixture.service().use { it.summary(fixture.query()) }
            try {
                assertNull(JsonScalars.str(result.getValue("goodput").jsonObject, "eligible"))
                assertEquals("false", JsonScalars.str(result.getValue("coverage").jsonObject, "complete"))
                assertTrue(result.toString().contains("evidence_duplicate_conflict"))
                val conflicts = result.getValue("coverage").jsonObject.getValue("issues").jsonArray.filter {
                    JsonScalars.str(it.jsonObject, "code") == "evidence_duplicate_conflict"
                }
                assertEquals(1, conflicts.size)
                assertEquals("1", JsonScalars.str(conflicts.single().jsonObject, "count"))
            } finally {
                PerfWorkBudget.release(result)
            }
        }
    }

    @Test
    fun `late canonical usage revises a later receipt without changing original completion count`(@TempDir root: Path) {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(1, withUsage = false)
            val before = fixture.service().use { it.summary(fixture.query()) }
            fixture.lateUsage()
            val after = fixture.service().use { it.summary(fixture.query()) }
            try {
                assertEquals(
                    "insufficient_evidence",
                    JsonScalars.str(before.getValue("comparison").jsonObject, "status"),
                )
                assertEquals("ready", JsonScalars.str(after.getValue("comparison").jsonObject, "status"))
                assertEquals("1", JsonScalars.str(before.getValue("counts").jsonObject, "completions"))
                assertEquals("1", JsonScalars.str(after.getValue("counts").jsonObject, "completions"))
            } finally {
                listOf(before, after).forEach(PerfWorkBudget::release)
            }
        }
    }
}

internal class StreamingEvidenceFixture(root: Path) : AutoCloseable, PerfQuerySources, PerfConfigProvenance {
    private val directory = root.resolve("evidence")
    private var now = 1_000L
    private var until = now
    private val wall = WallClock { now }
    private val files = RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now })
    private val writer = files.forHead("test")
    private val source = PerfEvidenceFileSource(directory, wall)
    private val identity = PerfIdentity("b".repeat(36), "test-build", "test-version", "boot-config")

    fun seed(count: Int, withUsage: Boolean = true) {
        assertTrue(AsyncFileIo.drain()) { "stage=before_seed io=${AsyncFileIo.workSnapshot}" }
        assertTrue(files.startCoverage("test", identity.bootId))
        repeat(count) { index ->
            val start = 1_000L + index * 100
            val kinds =
                listOf(
                    PerfEventKind.REQUEST_ARRIVED,
                    PerfEventKind.ATTEMPT_STARTED,
                    PerfEventKind.ATTEMPT_FINISHED,
                    PerfEventKind.REQUEST_FINISHED,
                )
            kinds.forEachIndexed { offset, kind ->
                now = start + offset * 20
                assertTrue(writer.record(event(index, kind, now)))
                settle(index, kind)
            }
            if (withUsage) {
                assertTrue(writer.record(event(index, PerfEventKind.USAGE_RECORDED, now)))
                settle(index, PerfEventKind.USAGE_RECORDED)
            }
        }
        until = 1_000L + count * 100
        now = until
        checkpoint()
    }

    private fun settle(index: Int, kind: PerfEventKind) {
        // The writer reads our virtual clock on the shared file lane; settle before advancing that clock again.
        val started = MonoClock.nowMs()
        assertTrue(AsyncFileIo.awaitDirectory(directory)) {
            "stage=seed_evidence request_index=$index kind=$kind barrier_elapsed_ms=${MonoClock.nowMs() - started} " +
                "interrupted=${Thread.currentThread().isInterrupted} evidence=${writer.sink.status()} " +
                "io=${AsyncFileIo.workSnapshot} work_budget=${PerfWorkBudget.snapshot()}"
        }
    }

    fun conflict() {
        now += 100
        val first = event(0, PerfEventKind.REQUEST_ARRIVED, 1_000L)
        assertTrue(
            writer.record(
                first.copy(
                    scope = first.scope.copy(model = "conflicting-model"),
                    links = first.links.copy(requestId = "another-request"),
                ),
            ),
        )
        checkpoint()
    }

    fun lateUsage() {
        now += 100
        assertTrue(writer.record(event(0, PerfEventKind.USAGE_RECORDED, 1_060L)))
        checkpoint()
    }

    private fun checkpoint() {
        assertTrue(AsyncFileIo.awaitDirectory(directory))
        assertTrue(files.checkpointCoverage())
        assertTrue(AsyncFileIo.awaitDirectory(directory))
    }

    fun query(): PerfQuery = PerfQuery(
        1_000,
        until,
        filters = mapOf("head" to "test"),
        objective = PerfObjectiveSet("latency", "r1", completionMs = 100.0),
        source = "evidence",
    )
    fun evidenceQuery(): PerfEvidenceQuery = PerfEvidenceQuery(1_000, until, "test")
    fun service(): PerfQueryService = PerfQueryService(this, clock = wall)
    override fun heads(): Set<String> = setOf("test")
    override fun rows(head: String): PerfRowsSource? = null
    override fun evidence(): PerfEvidenceFileSource = source
    override fun provenance(): PerfConfigProvenance = this
    override fun collectionMode(): String = "local"
    override fun comparisonScope(): PerfConfigComparisonScope = PerfConfigComparisonScope.EFFECTIVE_KNOB_POLICY
    override fun intervals(
        fromMs: Long,
        untilMs: Long,
    ): List<PerfConfigInterval> = listOf(PerfConfigInterval("test", 1_000, now, "knobs", identity.bootId))
    override fun close(): Unit = files.close()

    private fun event(index: Int, kind: PerfEventKind, at: Long): PerfObservation {
        val request = "r-" + index.toString().padStart(78, '0')
        val attempt = "a-" + index.toString().padStart(78, '0')
        val values = when (kind) {
            PerfEventKind.REQUEST_FINISHED -> mapOf("arrival_to_terminal_ms" to 60.0)
            PerfEventKind.ATTEMPT_FINISHED -> mapOf("attempt_duration_ms" to 20.0)
            PerfEventKind.USAGE_RECORDED -> usage()
            PerfEventKind.REQUEST_ARRIVED,
            PerfEventKind.REQUEST_ADMITTED,
            PerfEventKind.ATTEMPT_STARTED,
            PerfEventKind.PROTOCOL_PROGRESS,
            PerfEventKind.CONTENT_PROGRESS,
            PerfEventKind.CLIENT_PROGRESS,
            PerfEventKind.GAP,
            PerfEventKind.GATE_STATE,
            PerfEventKind.TOOL_STARTED,
            PerfEventKind.TOOL_FINISHED,
            PerfEventKind.TOOL_PHASE,
            PerfEventKind.RUNTIME,
            PerfEventKind.SELF_COST,
            PerfEventKind.CHECKPOINT,
            -> emptyMap()
        }
        return PerfObservation(
            PerfStamp("$request-${kind.name}", identity, at, at),
            PerfScope(
                head = "test",
                model = "model",
                transport = "http",
                local = false,
                compact = false,
                streaming = true,
            ),
            kind,
            values,
            PerfLinks(
                requestId = request,
                attemptId = attempt.takeIf {
                    kind in setOf(PerfEventKind.ATTEMPT_STARTED, PerfEventKind.ATTEMPT_FINISHED)
                },
                sessionId = "session",
                turnId = "legacy-$index",
                traceId = "1".repeat(32),
            ),
            if (kind in setOf(PerfEventKind.REQUEST_FINISHED, PerfEventKind.ATTEMPT_FINISHED)) "ok" else null,
        )
    }

    private fun usage(): Map<String, Double> = mapOf(
        "input_tokens" to 100.0, "output_tokens" to 5.0, "cached_tokens" to 20.0, "missing_usage" to 0.0,
        "usage_cache_write_tokens" to 10.0, "usage_cache_write_1h_tokens" to 0.0,
        "usage_absorbed_rounds" to 0.0, "usage_cut_rounds" to 0.0, "usage_no_request" to 0.0,
    ) + UsageMeasurementStamp.fields.keys.associateWith { 1.0 }
}
