// NEW: V4-345 — /api/perf/turns carries each row's trace turn id, so the console opens the request a
// person clicked by that id (GET /api/heads/{head}/trace?turn=ID). A row from a head that keeps no
// trace says so with a null, the absence every other named fact on the row reports (PerfRoutes.rowJson),
// never an empty string that would read as an id.
package splice.usage.perf.v4345

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.PerfRoutes
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

private const val TRACED = "3f2a9c01d4e5"

class PerfRowTurnWireTest {
    private val rows = listOf(
        PerfRow(
            ts = 1_000,
            outcome = "ok",
            fields = mapOf("total" to 5L, "in_tokens" to 1_000L, "out_tokens" to 100L),
            model = "m",
            turn = TRACED,
            sessionId = "sess-v4345",
            responseMessageId = "msg_42",
            cause = "CONTENT_FILTERED",
        ),
        PerfRow(ts = 2_000, outcome = "ok", fields = mapOf("total" to 7L), model = "m"),
    )

    private val head = UsageHead(
        key = "kimi",
        label = "claude-kimi",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warnPct = 80,
        warnTokens5h = 0,
        perfRows = PerfRowsSource { PerfRowsWindow(rows) },
        // why: 1,000 input and 100 output cost (1,000 + 400) / 1,000,000 USD.
        catalog = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(
                ModelEntry(
                    "m",
                    "M",
                    contextWindow = 256_000,
                    rates = ModelRates(input = 1.0, cacheRead = 0.1, output = 4.0),
                ),
            ),
            defaultContextWindow = 256_000,
        ),
    )

    private val heads = UsageHeadLookup { name -> if (name == "kimi") listOf(head) else emptyList() }

    private val routes = PerfRoutes(heads, WallClock { 3_000 })

    @Test
    fun `a row names its trace turn, and a row with none says so with a null`() = testApplication {
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }

        val response = client.get("/api/perf/turns?head=kimi&since=0")

        assertEquals(HttpStatusCode.OK, response.status)
        val served = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("heads").jsonArray.single().jsonObject
            .getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("CONTENT_FILTERED"), served[0]["cause"])
        assertEquals(JsonNull, served[1]["cause"], "a legacy row has no recorded cause")
        assertEquals(JsonPrimitive(TRACED), served[0]["turn"], "the traced row's id")
        assertEquals(JsonNull, served[1]["turn"], "no trace, a null")
        assertEquals(JsonPrimitive("sess-v4345"), served[0]["session_id"])
        assertEquals(JsonPrimitive("msg_42"), served[0]["response_message_id"])
        assertEquals(JsonNull, served[1]["session_id"])
        assertEquals(JsonNull, served[1]["response_message_id"])
        assertEquals(JsonPrimitive(0.0014), served[0]["cost_usd"], "the daemon prices this turn's counters")
        assertEquals(JsonNull, served[1]["cost_usd"], "a row without token counters has no known cost")
    }
}
