package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.UsageMeasurementStamp
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.history.PerfEvidenceSource

class PerfComparisonTest {
    @Test
    fun `billing zeros without measurement proof and excessive exact partitions stay insufficient`() {
        val fixture = ComparisonFixture()
        fixture.events = fixture.events.map {
            if (it.kind == PerfEventKind.USAGE_RECORDED) {
                it.copy(
                    values = it.values + ("usage_observed_cache_write" to 0.0),
                )
            } else {
                it
            }
        }
        val unproven = fixture.service().summary(fixture.query)
        fixture.events = (1..257).flatMap { fixture.request("request-$it", 100.0 + it) }
        val bounded = fixture.service().summary(fixture.query)
        try {
            assertNull(fingerprint(unproven))
            assertTrue(unproven.getValue("comparison").toString().contains("usage_measurement_presence_unknown"))
            val workload = bounded.getValue("comparison").jsonObject.getValue("workload").jsonObject
            assertEquals(256, workload.getValue("partitions").jsonArray.size)
            assertEquals("257", JsonScalars.str(workload, "observed_arrivals"))
            assertEquals("true", JsonScalars.str(workload, "truncated"))
            assertNull(fingerprint(bounded))
        } finally {
            listOf(unproven, bounded).forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `complete canonical joint workload is matchable with explicit environment limits`() {
        val fixture = ComparisonFixture()
        fixture.events = fixture.request("one") + fixture.request("two")
        val result = fixture.service().summary(fixture.query)
        try {
            val comparison = result.getValue("comparison").jsonObject
            val workload = comparison.getValue("workload").jsonObject
            assertEquals("ready", JsonScalars.str(comparison, "status"))
            assertEquals("2", JsonScalars.str(workload, "observed_arrivals"))
            assertEquals("2", JsonScalars.str(workload.getValue("partitions").jsonArray.single().jsonObject, "count"))
            assertNotNull(JsonScalars.str(workload, "distribution_id"))
            assertEquals("committed_observations_as_of", JsonScalars.str(workload, "coverage_basis"))
            assertEquals("false", JsonScalars.str(workload, "event_time_final"))
            assertEquals("unknown", JsonScalars.str(comparison.getValue("environment").jsonObject, "provider_topology"))
            assertEquals(
                "effective_knob_policy",
                JsonScalars.str(result.getValue("configuration").jsonObject, "comparison_scope"),
            )
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `exact frequencies match scaled populations but equal means cannot hide different joint facts`() {
        val fixture = ComparisonFixture()
        val first = fixture.service().summary(fixture.query)
        fixture.events = fixture.request("one") + fixture.request("two")
        val scaled = fixture.service().summary(fixture.query)
        fixture.events = fixture.request("one", input = 90.0) + fixture.request("two", input = 110.0)
        val sameMean = fixture.service().summary(fixture.query)
        try {
            assertEquals(fingerprint(first), fingerprint(scaled))
            assertNotEquals(fingerprint(first), fingerprint(sameMean))
            assertFalse(first.getValue("comparison").toString().contains("legacy-turn"))
        } finally {
            listOf(first, scaled, sameMean).forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `late usage unknown cache and absorbed totals never become invented exact sizes`() {
        val fixture = ComparisonFixture()
        val complete = fixture.events
        fixture.events = complete.filter { it.kind != PerfEventKind.USAGE_RECORDED }
        val missing = fixture.service().summary(fixture.query)
        fixture.events = complete.map {
            if (it.kind == PerfEventKind.USAGE_RECORDED) {
                it.copy(
                    values = it.values - "usage_cache_write_tokens",
                )
            } else {
                it
            }
        }
        val absentCache = fixture.service().summary(fixture.query)
        fixture.events = complete.map {
            if (it.kind == PerfEventKind.USAGE_RECORDED) {
                it.copy(
                    values = it.values + ("usage_absorbed_rounds" to 2.0),
                )
            } else {
                it
            }
        }
        val absorbed = fixture.service().summary(fixture.query)
        fixture.events = complete
        val arrivedLate = fixture.service().summary(fixture.query)
        try {
            listOf(missing, absentCache, absorbed).forEach {
                assertEquals("insufficient_evidence", JsonScalars.str(it.getValue("comparison").jsonObject, "status"))
                assertNull(fingerprint(it))
            }
            assertTrue(absorbed.getValue("comparison").toString().contains("absorbed_round_sizes_unknown"))
            assertNotNull(fingerprint(arrivedLate))
        } finally {
            listOf(missing, absentCache, absorbed, arrivedLate).forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `incomplete source unresolved cohort and mixed configuration prevent ready comparison`() {
        val fixture = ComparisonFixture()
        fixture.covered = false
        val partial = fixture.service().summary(fixture.query)
        fixture.covered = true
        fixture.events = fixture.events.map { it.copy(scope = it.scope.copy(model = null)) }
        val unresolved = fixture.service().summary(fixture.query.copy(filters = mapOf("model" to "m")))
        fixture.events = fixture.request("one")
        fixture.intervals = listOf(
            PerfConfigInterval("h", 100, 500, "config-a", "boot"),
            PerfConfigInterval("h", 500, 1_100, "config-b", "boot"),
        )
        val mixed = fixture.service().summary(fixture.query)
        try {
            assertNull(fingerprint(partial))
            assertTrue(unresolved.getValue("comparison").toString().contains("cohort_membership_unresolved"))
            assertEquals("insufficient_evidence", JsonScalars.str(mixed.getValue("comparison").jsonObject, "status"))
            assertEquals("mixed", JsonScalars.str(mixed.getValue("configuration").jsonObject, "status"))
        } finally {
            listOf(partial, unresolved, mixed).forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `timeline uses actual attached turn and head while retaining request identity`() {
        val fixture = ComparisonFixture()
        fixture.events = fixture.events.map {
            if (it.kind == PerfEventKind.REQUEST_ARRIVED) it.copy(links = PerfLinks(requestId = "one")) else it
        }
        val result = fixture.service().diagnose(fixture.query)
        try {
            val request = result.getValue("timeline").jsonObject.getValue("requests").jsonArray.single().jsonObject
            assertEquals("one", JsonScalars.str(request, "request_id"))
            assertEquals("legacy-turn-one", JsonScalars.str(request, "turn_id"))
            assertEquals("h", JsonScalars.str(request, "head"))
            assertEquals("trace-one", JsonScalars.str(request, "trace_id"))
            assertNull(JsonScalars.str(request.getValue("events").jsonArray.first().jsonObject, "turn_id"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `post window only requests cannot appear in or truncate requested timeline`() {
        val fixture = ComparisonFixture()
        fixture.horizon = 3_000
        fixture.now = 3_000
        fixture.events = (1..25).flatMap { fixture.request("later-$it", at = 2_500) }
        val query = fixture.query.copy(fromMs = 1_000, untilMs = 2_000)
        val result = fixture.service().diagnose(query)
        try {
            val timeline = result.getValue("timeline").jsonObject
            assertTrue(timeline.getValue("requests").jsonArray.isEmpty())
            assertEquals("false", JsonScalars.str(timeline, "truncated"))
            assertEquals("0", JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"))
            assertEquals("0", JsonScalars.str(result.getValue("counts").jsonObject, "completions"))
            assertEquals(3_000L, fixture.lastRead?.untilMs)
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `follow through retains late terminal usage without later requests displacing window representatives`() {
        val fixture = ComparisonFixture()
        fixture.horizon = 3_000
        fixture.now = 3_000
        fixture.intervals = listOf(PerfConfigInterval("h", 100, 3_000, "config", "boot"))
        val later = (1..25).flatMap { fixture.request("later-$it", at = 2_500) }
        val selected = fixture.request("selected", at = 1_500).map {
            when (it.kind) {
                PerfEventKind.REQUEST_FINISHED -> it.copy(
                    stamp = it.stamp.copy(wallMs = 2_500, monotonicMs = 2_500),
                    values = mapOf("arrival_to_terminal_ms" to 1_000.0),
                )
                PerfEventKind.USAGE_RECORDED -> it.copy(stamp = it.stamp.copy(wallMs = 2_501, monotonicMs = 2_501))
                else -> it
            }
        }
        fixture.events = later + selected
        val query = fixture.query.copy(
            fromMs = 1_000,
            untilMs = 2_000,
            objective = PerfObjectiveSet("completion", "r1", completionMs = 2_000.0),
        )
        val service = fixture.service()
        val result = service.diagnose(query)
        val summary = service.summary(query)
        try {
            val timeline = result.getValue("timeline").jsonObject
            val request = timeline.getValue("requests").jsonArray.single().jsonObject
            assertEquals("selected", JsonScalars.str(request, "request_id"))
            assertEquals("false", JsonScalars.str(timeline, "truncated"))
            val events = request.getValue("events").jsonArray.map { it.jsonObject }
            assertEquals(
                "2500",
                JsonScalars.str(events.single { JsonScalars.str(it, "kind") == "REQUEST_FINISHED" }, "wall_ms"),
            )
            assertEquals(
                "2501",
                JsonScalars.str(events.single { JsonScalars.str(it, "kind") == "USAGE_RECORDED" }, "wall_ms"),
            )
            assertEquals("1", JsonScalars.str(summary.getValue("counts").jsonObject, "arrivals"))
            assertEquals("0", JsonScalars.str(summary.getValue("counts").jsonObject, "completions"))
            assertEquals("1", JsonScalars.str(summary.getValue("goodput").jsonObject, "good"))
            assertEquals("1.0", JsonScalars.str(summary.getValue("goodput").jsonObject, "per_s"))
            assertEquals("ready", JsonScalars.str(summary.getValue("comparison").jsonObject, "status"))
        } finally {
            listOf(result, summary).forEach(PerfWorkBudget::release)
        }
    }

    @Test
    fun `a request completing in the window remains representative when its arrival precedes the window`() {
        val fixture = ComparisonFixture()
        fixture.horizon = 3_000
        fixture.now = 3_000
        fixture.events = fixture.request("overlap", at = 1_500).map {
            if (it.kind == PerfEventKind.REQUEST_ARRIVED) {
                it.copy(stamp = it.stamp.copy(wallMs = 900, monotonicMs = 900))
            } else {
                it
            }
        }
        val result = fixture.service().diagnose(fixture.query.copy(fromMs = 1_000, untilMs = 2_000))
        try {
            val timeline = result.getValue("timeline").jsonObject
            assertEquals(
                "overlap",
                JsonScalars.str(timeline.getValue("requests").jsonArray.single().jsonObject, "request_id"),
            )
            assertEquals("false", JsonScalars.str(timeline, "truncated"))
            assertEquals("0", JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"))
            assertEquals("1", JsonScalars.str(result.getValue("counts").jsonObject, "completions"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `certified horizon bounds follow through but never rewrites future requested window`() {
        val fixture = ComparisonFixture()
        val service = fixture.service()
        assertEquals(1_100L, service.settledUntilMs("h"))
        val completed = service.summary(fixture.query)
        assertEquals(1_100L, fixture.lastRead?.untilMs)
        val future = service.summary(fixture.query.copy(untilMs = 1_500))
        try {
            assertEquals(1_500L, fixture.lastRead?.untilMs)
            assertEquals("1000", JsonScalars.str(completed, "requested_duration_ms"))
            assertEquals("1100", JsonScalars.str(completed, "settled_until_ms"))
            assertEquals("true", JsonScalars.str(completed.getValue("coverage").jsonObject, "complete"))
            assertEquals("1400", JsonScalars.str(future, "requested_duration_ms"))
            assertEquals("false", JsonScalars.str(future.getValue("coverage").jsonObject, "complete"))
            assertNull(fingerprint(future))
        } finally {
            listOf(completed, future).forEach(PerfWorkBudget::release)
        }
    }

    private fun fingerprint(result: JsonObject): String? =
        JsonScalars.str(result.getValue("comparison").jsonObject.getValue("workload").jsonObject, "distribution_id")
}

private class ComparisonFixture : PerfQuerySources, PerfEvidenceSource, PerfConfigProvenance {
    val query = PerfQuery(100, 1_100, source = "evidence")
    var events = request("one")
    var covered = true
    var horizon = 1_100L
    var now = 2_000L
    var lastRead: PerfEvidenceQuery? = null
    var intervals = listOf(PerfConfigInterval("h", 100, 1_100, "config", "boot"))

    fun service(): PerfQueryService = PerfQueryService(this, clock = WallClock { now })
    override fun heads(): Set<String> = setOf("h")
    override fun rows(head: String): PerfRowsSource? = null
    override fun evidence(): PerfEvidenceSource = this
    override fun provenance(): PerfConfigProvenance = this
    override fun comparisonScope(): PerfConfigComparisonScope = PerfConfigComparisonScope.EFFECTIVE_KNOB_POLICY
    override fun intervals(fromMs: Long, untilMs: Long): List<PerfConfigInterval> = intervals
    override fun settledUntilMs(head: String?): Long = horizon
    override fun read(query: PerfEvidenceQuery): PerfEvidenceRead {
        lastRead = query
        val complete = covered && query.untilMs <= horizon
        return PerfEvidenceRead(
            events.filter { it.stamp.wallMs in query.fromMs until query.untilMs },
            PerfCoverage(query.fromMs, query.untilMs, if (covered) minOf(query.untilMs, horizon) - query.fromMs else 0),
            horizon,
            complete,
            "committed_observations_as_of",
        )
    }

    fun request(id: String, input: Double = 100.0, at: Long = 101): List<PerfObservation> = listOf(
        PerfEventKind.REQUEST_ARRIVED,
        PerfEventKind.REQUEST_FINISHED,
        PerfEventKind.USAGE_RECORDED,
    ).mapIndexed { index, kind ->
        PerfObservation(
            PerfStamp(
                "$id-$index",
                PerfIdentity("boot", "build", "version", "boot-config"),
                at + index,
                at + index,
            ),
            PerfScope(head = "h", model = "m", transport = "http", local = false, compact = false, streaming = true),
            kind,
            if (kind == PerfEventKind.USAGE_RECORDED) {
                mapOf(
                    "input_tokens" to input, "output_tokens" to 20.0, "cached_tokens" to 10.0, "missing_usage" to 0.0,
                    "usage_cache_write_tokens" to 5.0, "usage_cache_write_1h_tokens" to 0.0,
                    "usage_absorbed_rounds" to 0.0, "usage_cut_rounds" to 0.0, "usage_no_request" to 0.0,
                ) + UsageMeasurementStamp.fields.keys.associateWith { 1.0 }
            } else {
                emptyMap()
            },
            PerfLinks(requestId = id, turnId = "legacy-turn-$id", traceId = "trace-$id", sessionId = "session-$id"),
            "ok",
        )
    }
}
