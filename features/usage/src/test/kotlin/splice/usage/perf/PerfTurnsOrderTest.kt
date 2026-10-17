// NEW: /api/perf/turns?order=price keeps the n DEAREST requests of the whole matching window (what each costs at the
// head's published API rates), unpriced last, and the default still keeps the newest n.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfTurnsOrderTest {
    private fun row(ts: Long, inTokens: Long?) = PerfRow(
        ts = ts,
        outcome = "ok",
        fields = listOfNotNull(
            "total" to 5L,
            inTokens?.let { "in_tokens" to it },
            inTokens?.let { "out_tokens" to 0L },
            inTokens?.let { "cached_tokens" to 0L },
            inTokens?.let { "cache_write_tokens" to 0L },
        ).toMap(),
        facts = PerfTurnFacts(model = "m"),
    )

    // Oldest is dearest, the middle one is cheap, the newest has no token counts to price.
    private val rows = listOf(row(1_000, 9_000_000), row(2_000, 1_000_000), row(3_000, 5_000_000), row(4_000, null))

    private val head = UsageHead(
        key = "kimi",
        label = "claude-kimi",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = PerfRowsSource { PerfRowsWindow(rows) }),
        statusline = UsageHeadStatusline(
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
        ),
    )

    private val routes =
        PerfRoutes(UsageHeadLookup { name -> if (name == "kimi") listOf(head) else emptyList() }, WallClock { 5_000 })

    private suspend fun io.ktor.client.HttpClient.stamps(query: String): List<Long> =
        Json.parseToJsonElement(get("/api/perf/turns?head=kimi&since=0&$query").bodyAsText()).jsonObject
            .getValue("heads").jsonArray.single().jsonObject.getValue("rows").jsonArray.map {
                it.jsonObject.getValue("ts").jsonPrimitive.content.toLong()
            }

    @Test
    fun `price keeps the dearest of the window, unpriced last, and the default keeps the newest`() = testApplication {
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }

        assertEquals(listOf(1_000L, 3_000L), client.stamps("n=2&order=price"), "the dearest two, from the whole window")
        assertEquals(listOf(1_000L, 3_000L, 2_000L, 4_000L), client.stamps("n=4&order=price"), "unpriced last")
        assertEquals(listOf(3_000L, 4_000L), client.stamps("n=2"), "no order keeps the newest two")
        val answered = Json.parseToJsonElement(
            client.get("/api/perf/turns?head=kimi&since=0&n=2&order=price").bodyAsText(),
        ).jsonObject
            .getValue("heads").jsonArray.single().jsonObject
        assertEquals(
            "price",
            answered.getValue("order").jsonPrimitive.content,
            "the answer echoes the order it applied",
        )
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/perf/turns?head=kimi&since=0&order=biggest").status)
    }
}
