package splice.usage.perf.query

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.history.PerfEvidenceSource

class PerfQueryEvidenceTest {
    @Test
    fun `refused before legacy turn and canonical late usage are counted once at their own boundaries`() {
        val events = listOf(
            event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED),
            event(
                "b",
                "one",
                201,
                PerfEventKind.REQUEST_FINISHED,
                "ok",
            ).copy(values = mapOf("arrival_to_terminal_ms" to 100.0)),
            event("c", "one", 201, PerfEventKind.USAGE_RECORDED, "ok").copy(values = mapOf("output_tokens" to 50.0)),
            event("d", "two", 301, PerfEventKind.REQUEST_ARRIVED),
            event("e", "two", 401, PerfEventKind.REQUEST_FINISHED, "gate_refused"),
        )
        val service = service(events + events[2])
        val result = service.summary(PerfQuery(100, 1_100, source = "evidence"))
        assertEquals("2", JsonScalars.str(result.getValue("counts").jsonObject, "completions"))
        assertEquals("2.0", JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        assertEquals("50.0", JsonScalars.str(result.getValue("rates").jsonObject, "output_tokens_per_s"))
        assertEquals("unconfigured", JsonScalars.str(result.getValue("goodput").jsonObject, "status"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `full session IDs filter before objective classification and request counts`() {
        val events = listOf(
            event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED),
            event(
                "b",
                "one",
                201,
                PerfEventKind.REQUEST_FINISHED,
                "ok",
            ).copy(values = mapOf("arrival_to_terminal_ms" to 100.0)),
            event(
                "c",
                "two",
                301,
                PerfEventKind.REQUEST_ARRIVED,
            ).copy(links = PerfLinks(requestId = "two", sessionId = "different")),
        )
        val query =
            PerfQuery(
                100,
                1_100,
                mapOf("session" to "full-session"),
                PerfObjectiveSet("latency", "r1", completionMs = 200.0),
                "evidence",
            )
        val result = service(events).summary(query)
        assertEquals("1", JsonScalars.str(result.getValue("goodput").jsonObject, "eligible"))
        assertEquals("1", JsonScalars.str(result.getValue("goodput").jsonObject, "good"))
        assertEquals("1.0", JsonScalars.str(result.getValue("goodput").jsonObject, "per_s"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `partial evidence never claims a complete eligible request population`() {
        val result = service(listOf(event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED)), false)
            .summary(
                PerfQuery(
                    100,
                    1_100,
                    objective = PerfObjectiveSet("latency", "r1", completionMs = 200.0),
                    source = "evidence",
                ),
            )
        assertNull(JsonScalars.str(result.getValue("goodput").jsonObject, "eligible"))
        assertEquals("1", JsonScalars.str(result.getValue("goodput").jsonObject, "unknown"))
        assertEquals("partial", JsonScalars.str(result, "status"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `known terminal verdict survives unknown collection duration without claiming full goodput`() {
        val events = listOf(
            event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED),
            event("b", "one", 201, PerfEventKind.REQUEST_FINISHED, "ok")
                .copy(values = mapOf("arrival_to_terminal_ms" to 100.0)),
        )
        val query = PerfQuery(
            100,
            1_100,
            objective = PerfObjectiveSet("latency", "r1", completionMs = 200.0),
            source = "evidence",
        )
        val result = service(events, covered = false).summary(query)
        val goodput = result.getValue("goodput").jsonObject
        assertEquals("1", JsonScalars.str(goodput, "good"))
        assertEquals("1", JsonScalars.str(goodput, "known_evaluable"))
        assertEquals("1.0", JsonScalars.str(goodput, "known_good_per_s"))
        assertEquals("true", JsonScalars.str(goodput, "known_good_rate_is_lower_bound"))
        assertNull(JsonScalars.str(goodput, "eligible"))
        assertNull(JsonScalars.str(goodput, "per_s"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `possibly matching missing model limits exact eligibility without erasing known good requests`() {
        val known = listOf(
            event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED),
            event("b", "one", 201, PerfEventKind.REQUEST_FINISHED, "ok")
                .copy(values = mapOf("arrival_to_terminal_ms" to 100.0)),
        )
        val unresolved = event("c", "two", 301, PerfEventKind.REQUEST_ARRIVED)
            .copy(scope = PerfScope(head = "h"))
        val query = PerfQuery(
            100,
            1_100,
            filters = mapOf("model" to "m"),
            objective = PerfObjectiveSet("latency", "r1", completionMs = 200.0),
            source = "evidence",
        )
        val result = service(known + unresolved).summary(query)
        try {
            val goodput = result.getValue("goodput").jsonObject
            assertEquals("1", JsonScalars.str(goodput, "good"))
            assertNull(JsonScalars.str(goodput, "eligible"))
            assertTrue(result.toString().contains("request_cohort_membership_unknown"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `known excluded model cannot make selected streaming cohort membership unknown`() {
        val included = listOf(
            event("a", "one", 101, PerfEventKind.REQUEST_ARRIVED),
            event("b", "one", 201, PerfEventKind.REQUEST_FINISHED, "ok")
                .copy(values = mapOf("arrival_to_terminal_ms" to 100.0)),
        ).map { it.copy(scope = it.scope.copy(streaming = true)) }
        val excluded = event("c", "two", 301, PerfEventKind.REQUEST_ARRIVED)
            .copy(scope = PerfScope(head = "h", model = "other", streaming = null))
        val query = PerfQuery(
            100,
            1_100,
            filters = mapOf("model" to "m", "streaming" to "true"),
            objective = PerfObjectiveSet("latency", "r1", completionMs = 200.0),
            source = "evidence",
        )
        val result = service(included + excluded).summary(query)
        try {
            assertEquals("1", JsonScalars.str(result.getValue("goodput").jsonObject, "eligible"))
            assertEquals("1.0", JsonScalars.str(result.getValue("goodput").jsonObject, "per_s"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `short windows and malformed parameters are consistent and credential free`() {
        assertEquals(
            60_000L,
            PerfQueryParser.parse(mapOf("window" to "1m"), 90_000).query?.let { it.untilMs - it.fromMs },
        )
        val refused = PerfQueryParser.parse(mapOf("streaming" to "secret\u001b[31m"), 90_000)
        assertNull(refused.query)
        assertFalse(refused.error.toString().contains("secret"))
        assertTrue(refused.error.toString().contains("invalid"))
    }

    private fun service(
        events: List<PerfObservation>,
        complete: Boolean = true,
        covered: Boolean = complete,
    ): PerfQueryService = PerfQueryService(
        object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun evidence(): PerfEvidenceSource = PerfEvidenceSource { query ->
                PerfEvidenceRead(
                    events,
                    PerfCoverage(query.fromMs, query.untilMs, if (covered) query.untilMs - query.fromMs else 0),
                    1_100,
                    complete,
                )
            }
        },
        clock = WallClock { 1_100 },
    )

    private fun event(
        id: String,
        request: String,
        at: Long,
        kind: PerfEventKind,
        outcome: String? = null,
    ): PerfObservation =
        PerfObservation(
            PerfStamp(id, PerfIdentity("boot", "build", "version", "boot-config"), at, at),
            PerfScope(head = "h", model = "m", transport = "http", local = false, compact = false),
            kind,
            emptyMap(),
            PerfLinks(requestId = request, sessionId = "full-session"),
            outcome,
        )
}
