// Oct 11, 2026 (fin): /api/economics keeps a plan's requests apart from a key's, and says per command whether every
// account is a plan account, so the Day row can say who paid for an hour that was never split.
package splice.usage.economics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PlanSplitPayloadTest {
    private fun pool(vararg plans: String?) = HeadAccountPoolSource {
        HeadAccountPoolView(
            "a0",
            plans.mapIndexed { i, plan -> HeadAccountView("a$i", i == 0, true, true, plan) },
            null,
        )
    }

    private val split = EconomicsRow(
        hour = 3_600_000,
        counts = EconomicsTurnCounts(turns = 4),
        tokens = EconomicsTokens(0, 0, 0, 0),
        bytes = EconomicsBytes(0, 0),
        tools = EconomicsTools(0, 0, 0),
        rateLimited = 0,
        cost = EconomicsCost(
            costUsd = 1.5,
            unpricedTurns = 1,
            planTurns = 2,
            planValueUsd = 40.0,
            planUnpricedTurns = 1,
        ),
    )
    private val unsplit = split.copy(hour = 7_200_000, cost = EconomicsCost(costUsd = 9.0, unpricedTurns = 3))

    private fun head(key: String, accounts: HeadAccountPoolSource?) = UsageHead(
        key,
        key,
        HeadUsageSource { UsageView(0L, 0, null, null) },
        UsageHeadWarn(0, 0),
        sinks = UsageHeadSinks(
            accountPool = accounts,
            economics = HeadEconomicsSource { EconomicsRead.Rows(listOf(split, unsplit)) },
        ),
    )

    private val heads = listOf(
        head("mixed", pool("max", null)),
        head("plans", pool("max", "pro")),
        head("subscription", null),
        head("local", pool("max")),
    )
    private val lookup = object : UsageHeadLookup {
        override fun byName(name: String) = heads.filter { it.key == name }

        override fun billing(key: String) = when (key) {
            "subscription" -> UsageBilling.SUBSCRIPTION
            "local" -> UsageBilling.LOCAL_RUNTIME
            else -> UsageBilling.API_RATE
        }
    }

    private fun answer(): Map<String, JsonObject> =
        Json.parseToJsonElement(EconomicsPayloads(UsageHeads { heads }, lookup = lookup).economicsJson())
            .jsonObject.getValue("heads").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("key").jsonPrimitive.content }

    @Test
    fun `a command's flag says whether every account it can use has a plan`() {
        assertEquals(
            mapOf("mixed" to false, "plans" to true, "subscription" to true, "local" to false),
            answer().mapValues { it.value.getValue("all_accounts_on_plan").jsonPrimitive.content.toBoolean() },
        )
    }

    @Test
    fun `a split hour carries its plan share apart and an unsplit hour carries none`() {
        val buckets = answer().getValue("mixed").getValue("buckets").jsonArray.map { it.jsonObject }
        val (a, b) = buckets
        assertEquals("1.5", a.getValue("cost_usd").jsonPrimitive.content)
        assertEquals("1", a.getValue("unpriced_turns").jsonPrimitive.content)
        assertEquals("2", a.getValue("plan_turns").jsonPrimitive.content)
        assertEquals("40.0", a.getValue("plan_value_usd").jsonPrimitive.content)
        listOf("plan_turns", "plan_value_usd").forEach {
            assertEquals(JsonNull, b.getValue(it), "$it is null where the hour was never split")
        }
    }
}
