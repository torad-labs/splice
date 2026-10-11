// Oct 11, 2026 (fin): on /api/perf/turns a plan account's request is a plan request whether the card prices it or
// not, with its API-rate value kept apart, and the window's cost is the key accounts' alone.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfPlanSplitTest {
    private val pool = HeadAccountPoolSource {
        HeadAccountPoolView(
            "plan",
            listOf(HeadAccountView("plan", true, true, true, "pro"), HeadAccountView("key", false, false, true, null)),
            null,
        )
    }
    private val tokens = mapOf(
        PerfKeys.IN_TOKENS to 1_000L,
        PerfKeys.CACHED_TOKENS to 900L,
        PerfKeys.OUT_TOKENS to 100L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
    )

    private fun row(ts: Long, account: String, model: String) = PerfRow(
        ts = ts,
        outcome = "ok",
        fields = tokens,
        facts = PerfTurnFacts(model = model, account = account),
        transcript = PerfTranscriptLink(sessionId = "s"),
    )

    private fun JsonObject.text(name: String) = getValue(name).takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `a plan account's priced request is a plan request with a value, and a key account's is a cost`() =
        testApplication {
            val rows = listOf(row(1_001, "plan", "m"), row(1_002, "key", "m"), row(1_003, "plan", "free"))
            val catalog = ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(
                    ModelEntry("m", contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0)),
                    ModelEntry("free", contextWindow = 100_000),
                ),
                defaultContextWindow = 100_000,
            )
            val head = UsageHead(
                key = "synthetic",
                label = "Synthetic",
                usage = HeadUsageSource { UsageView(0, 0, null) },
                warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
                sinks = UsageHeadSinks(
                    perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) },
                    accountPool = pool,
                ),
                statusline = UsageHeadStatusline(catalog = catalog),
            )
            val lookup = object : UsageHeadLookup {
                override fun byName(name: String): List<UsageHead> = listOf(head)

                override fun billing(key: String): UsageBilling = UsageBilling.API_RATE
            }
            val routes = PerfRoutes(lookup, WallClock { 5_000 })
            application { routing { get("/api/perf/turns") { routes.turns(call) } } }

            val body = client.get("/api/perf/turns?head=synthetic&since=1000&local=0").bodyAsText()
            val block = Json.parseToJsonElement(body)
                .jsonObject.getValue("heads").jsonArray.single().jsonObject
            val byTs = block.getValue("rows").jsonArray.map { it.jsonObject }
                .associateBy { it.getValue("ts").jsonPrimitive.long }

            val plan = byTs.getValue(1_001L)
            assertEquals("plan", plan.text("cost_reason"))
            assertEquals(null, plan.text("cost_usd"), "a plan billed nothing")
            assertEquals("5.9E-4", plan.text("plan_value_usd"), "what the API rates would charge")
            val key = byTs.getValue(1_002L)
            assertEquals(null, key.text("cost_reason"))
            assertEquals("5.9E-4", key.text("cost_usd"), "a key was charged")
            assertEquals(null, key.text("plan_value_usd"))
            val unpriced = byTs.getValue(1_003L)
            assertEquals("plan", unpriced.text("cost_reason"), "the card has no price for it, and a plan covered it")
            assertEquals(null, unpriced.text("plan_value_usd"))

            val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
            assertEquals("2", totals.text("plan_requests"))
            assertEquals("1", totals.text("plan_valued_requests"))
            assertEquals("5.9E-4", totals.text("plan_value_usd"))
            assertEquals("5.9E-4", totals.text("cost_usd"), "the cost is the key request alone")
        }
}
