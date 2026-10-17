// A surface that sent a model a question itself reads its reply's price from the daemon: a key's figure, a plan's
// value kept apart from any cost, and the reason a reply has none, the way a recorded request on Requests reads.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.util.WallClock
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfReplyPriceTest {
    private val pool = HeadAccountPoolSource {
        HeadAccountPoolView(
            "plan",
            listOf(HeadAccountView("plan", true, true, true, "pro"), HeadAccountView("key", false, false, true, null)),
            null,
        )
    }

    private val catalog = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(
            ModelEntry("m", contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0)),
            ModelEntry("free", contextWindow = 100_000),
        ),
        defaultContextWindow = 100_000,
    )

    private fun JsonObject.text(name: String) = getValue(name).takeIf { it != JsonNull }?.jsonPrimitive?.content

    private fun ApplicationTestBuilder.wired() {
        val head = UsageHead(
            key = "synthetic",
            label = "Synthetic",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
            sinks = UsageHeadSinks(accountPool = pool),
            statusline = UsageHeadStatusline(catalog = catalog),
        )
        val lookup = object : UsageHeadLookup {
            override fun byName(name: String): List<UsageHead> = listOf(head)

            override fun billing(key: String): UsageBilling = UsageBilling.API_RATE
        }
        val routes = PerfRoutes(lookup, WallClock { 5_000 })
        application { routing { get("/api/perf/price") { routes.price(call) } } }
    }

    private suspend fun ApplicationTestBuilder.priced(path: String): Pair<HttpStatusCode, JsonObject> {
        val response = client.get(path)
        return response.status to Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    @Test
    fun `a reply on a key account is a cost, and on a plan account a value and never a cost`() = testApplication {
        wired()
        val key = priced("/api/perf/price?head=synthetic&account=key&model=m&in_tokens=1000&out_tokens=100").second
        assertEquals("0.0014", key.text("cost_usd"))
        assertEquals(null, key.text("cost_reason"))
        assertEquals(null, key.text("plan_value_usd"))
        val plan = priced("/api/perf/price?head=synthetic&account=plan&model=m&in_tokens=1000&out_tokens=100").second
        assertEquals(null, plan.text("cost_usd"))
        assertEquals("plan", plan.text("cost_reason"))
        assertEquals("0.0014", plan.text("plan_value_usd"))
    }

    @Test
    fun `a reply the card has no price for says why, and a bad question is refused`() = testApplication {
        wired()
        val free = priced("/api/perf/price?head=synthetic&account=key&model=free&in_tokens=10&out_tokens=5").second
        assertEquals(null, free.text("cost_usd"))
        assertEquals("undeclared", free.text("cost_reason"))
        val (status, refusal) = priced("/api/perf/price?head=synthetic&model=m&in_tokens=ten&out_tokens=5")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("in_tokens and out_tokens are both required numbers", refusal.text("error"))
    }
}
