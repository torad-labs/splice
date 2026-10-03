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
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
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
    private val rows = listOf(failure, unknown, bare, local) + oks

    private val head = UsageHead(
        key = "kimi",
        label = "claude-kimi",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warnPct = 80,
        warnTokens5h = 0,
        perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) },
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
            model = "opus",
            account = "work",
            session = session,
            compact = compact,
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
    fun `Failed returns the one failure in the window however many newer rows fill the list`() = testApplication {
        mount()
        val head = ask(client, "n=2000&outcome=failed")
        assertEquals(listOf(5_000L), stamps(head), "the failure, and not the unattributed `?` row")
        assertEquals(1L, count(head), "the count is the rows the filter matches")
    }

    @Test
    fun `an outcome tag, ok, a model, an account and a session each narrow the window`() = testApplication {
        mount()
        assertEquals(listOf(5_000L), stamps(ask(client, "outcome=error:rate-limited")))
        assertEquals(2_003L, count(ask(client, "outcome=ok")), "2,001 ok rows, the bare row and the local step")
        assertEquals(listOf(5_000L), stamps(ask(client, "session=s2")))
        assertEquals(2_004L, count(ask(client, "model=opus&account=work")), "every row but the bare one")
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
        assertEquals(listOf(5_000L, 6_000L, 7_000L), stamps(head))
        assertEquals(3L, count(head))
    }

    @Test
    fun `compact=1 finds the compactions in the window, and compact=0 every request that was not one`() = testApplication {
        mount()
        val compacted = ask(client, "compact=1")
        assertEquals(listOf(6_000L), stamps(compacted))
        assertEquals(1L, count(compacted))
        val rest = stamps(ask(client, "until=10000&compact=0"))
        assertEquals(listOf(5_000L, 7_000L, 8_000L), rest, "a row with no compact field is not one")
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
