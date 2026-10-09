// V4-444: the console's Requests list filters on the daemon. A filter run in the browser over the newest-n
// slice the route already cut finds nothing older than the slice: the header counted 3 failed requests in
// the last hour while "Failed" showed none, because 2,000 ok rows filled the list. Every filter here narrows
// the WINDOW before the clamp, and `count` is the rows the filters match, so the list and its header agree.
package splice.usage.perf

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfTurnsFilterTest {
    // why: one more ok row than the route's ceiling of 2,000, all NEWER than the one failure, so the
    // newest-n slice holds none of it.
    private val oks = (1..2_001).map { i -> row(ts = 10_000L + i, outcome = "ok", session = "s1") }
    private val failure = row(ts = 5_000L, outcome = "error:rate-limited", session = "s2")
    private val unknown = row(ts = 6_000L, outcome = "?", session = "s1", compact = true)
    private val bare = PerfRow(ts = 7_000L, outcome = "ok", fields = emptyMap())
    private val local = row(ts = 8_000L, outcome = "ok", session = "s1", fields = mapOf(PerfKeys.LOCAL_STEP to 1L))
    private val stops = listOf(OutcomeTag.CLIENT_ABORT.wire, OutcomeTags.error("stopped")).mapIndexed { index, tag ->
        row(ts = 9_100L + index * 100L, outcome = tag, session = "stops")
    }
    private val cancelled = row(ts = 9_300L, outcome = OutcomeTag.CANCELLED.wire, session = "watchdog")

    // why: a model that closed an empty message ended the turn clean for the client. Its own model and a time past
    // every until=10000 window keep it out of the other filters' counts.
    private val emptyAnswer = PerfRow(
        ts = 12_500L,
        outcome = OutcomeTag.EMPTY_MESSAGE.wire,
        fields = emptyMap(),
        facts = PerfTurnFacts(model = "sonnet", session = "empty", account = "work"),
    )
    private val rows = listOf(failure, unknown, bare, local) + stops + listOf(cancelled, emptyAnswer) + oks

    private val head = UsageHead(
        key = "kimi",
        label = "claude-kimi",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) }),
    )
    private val routes = PerfRoutes(
        UsageHeadLookup { name -> if (name == "kimi") listOf(head) else emptyList() },
        WallClock { 20_000 },
    )

    private fun row(
        ts: Long,
        outcome: String,
        session: String,
        fields: Map<String, Long> = emptyMap(),
        compact: Boolean = false,
    ): PerfRow =
        PerfRow(
            ts = ts,
            outcome = outcome,
            fields = fields,
            facts = PerfTurnFacts(model = "opus", session = session, account = "work", compact = compact),
        )

    private fun ApplicationTestBuilder.mount() {
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }
    }

    private suspend fun ask(client: HttpClient, query: String): JsonObject {
        val response = client.get("/api/perf/turns?head=kimi&since=0&$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("heads").jsonArray.single().jsonObject
    }

    private fun stamps(head: JsonObject): List<Long> =
        head.getValue("rows").jsonArray.map { it.jsonObject.getValue("ts").jsonPrimitive.long }

    private fun count(head: JsonObject): Long = head.getValue("count").jsonPrimitive.long

    @Test
    fun `Requests uses window attributed rejections without reporting historical file health as missing requests`() =
        testApplication {
            var windowSkipped = 0
            val measured = UsageHead(
                key = "kimi",
                label = "claude-kimi",
                usage = HeadUsageSource { UsageView(0, 0, null) },
                warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
                sinks = UsageHeadSinks(
                    perfRows = PerfRowsSource {
                        PerfRowsWindow(emptyList(), skipped = 3, windowSkipped = windowSkipped)
                    },
                ),
            )
            val measuredRoutes = PerfRoutes(UsageHeadLookup { listOf(measured) }, WallClock { 20_000 })
            application { routing { get("/api/perf/turns") { measuredRoutes.turns(call) } } }
            assertEquals(null, ask(client, "")["skipped_lines"], "historical file health is not window request loss")
            windowSkipped = 1
            assertEquals(1L, ask(client, "").getValue("skipped_lines").jsonPrimitive.long)
        }

    @Test
    fun `Failed includes watchdog cancellation but excludes stops however many newer rows fill the list`() = testApplication {
        mount()
        val head = ask(client, "n=2000&outcome=failed")
        assertEquals(
            listOf(5_000L, 9_300L),
            stamps(head),
            "the refusal and watchdog cancellation, never stops, `?` or a clean empty answer",
        )
        assertEquals(2L, count(head), "the count is the rows the filter matches")
    }

    @Test
    fun `Stopped finds every stopped outcome before the clamp with its full matched count`() = testApplication {
        mount()
        val stopped = ask(client, "n=1&outcome=stopped&local=0")
        assertEquals(listOf(9_200L), stamps(stopped), "newest stop, not watchdog cancellation or newer successes")
        assertEquals(2L, count(stopped), "all stopped requests count before the newest-n clamp")
        assertEquals(listOf(9_100L), stamps(ask(client, "outcome=client_abort")), "exact tag filters stay exact")
        assertEquals(listOf(9_100L), stamps(ask(client, "outcome=stopped&until=9200")), "until stays exclusive")
    }

    @Test
    fun `an outcome tag, ok, a model, an account and a session each narrow the window`() = testApplication {
        mount()
        assertEquals(listOf(5_000L), stamps(ask(client, "outcome=error:rate-limited")))
        assertEquals(2_003L, count(ask(client, "outcome=ok")), "2,001 ok rows, the bare row and the local step")
        assertEquals(listOf(5_000L), stamps(ask(client, "session=s2")))
        assertEquals(2_007L, count(ask(client, "model=opus&account=work")), "every row but the bare one")
    }

    @Test
    fun `until closes the window, and a row with no model or account is found as unattributed`() = testApplication {
        mount()
        assertEquals(listOf(5_000L, 6_000L), stamps(ask(client, "until=7000")), "until is exclusive")
        assertEquals(listOf(7_000L), stamps(ask(client, "unattributed=model")))
        assertEquals(listOf(7_000L), stamps(ask(client, "unattributed=account")))
    }

    @Test
    fun `local=0 leaves out the steps splice answered itself, and the count agrees`() = testApplication {
        mount()
        val head = ask(client, "until=10000&local=0")
        assertEquals(listOf(5_000L, 6_000L, 7_000L, 9_100L, 9_200L, 9_300L), stamps(head))
        assertEquals(6L, count(head))
    }

    @Test
    fun `compact=1 finds the compactions in the window, and compact=0 every request that was not one`() = testApplication {
        mount()
        val compacted = ask(client, "compact=1")
        assertEquals(listOf(6_000L), stamps(compacted))
        assertEquals(1L, count(compacted))
        val rest = stamps(ask(client, "until=10000&compact=0"))
        assertEquals(
            listOf(5_000L, 7_000L, 8_000L, 9_100L, 9_200L, 9_300L),
            rest,
            "a row with no compact field is not one",
        )
    }

    @Test
    fun `a filter spelled wrong is refused by name, never ignored`() = testApplication {
        mount()
        val spelled = listOf(
            "until=soon" to "soon",
            "unattributed=session" to "session",
            "local=maybe" to "maybe",
            "compact=yes" to "yes",
        )
        for ((query, named) in spelled) {
            val response = client.get("/api/perf/turns?head=kimi&since=0&$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertTrue(response.bodyAsText().contains(named), response.bodyAsText())
        }
    }
}
