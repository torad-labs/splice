// NEW: Oct 10, 2026 — the console's Day row says why a command's turns with no price have none: a plan's read
// "on your plan", and only a command on a model with no rate card reads "with no price" (Marlin). The daemon
// gives the reason on /api/economics, decided by the rule the Requests page uses.
package splice.usage.economics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class UnpricedReasonTest {

    private fun head(key: String) =
        UsageHead(key, key, HeadUsageSource { UsageView(0L, 0, null, null) }, UsageHeadWarn(0, 0))

    private val heads = listOf(head("claudex"), head("claude-deepseek"), head("claude-bonsai"))
    private val billing = mapOf(
        "claudex" to UsageBilling.SUBSCRIPTION,
        "claude-deepseek" to UsageBilling.API_RATE,
        "claude-bonsai" to UsageBilling.LOCAL_RUNTIME,
    )
    private val lookup = object : UsageHeadLookup {
        override fun byName(name: String) = heads.filter { it.key == name }

        override fun billing(key: String) = billing[key]
    }

    private fun reasons(payloads: EconomicsPayloads): Map<String, String?> =
        Json.parseToJsonElement(payloads.economicsJson()).jsonObject.getValue("heads").jsonArray.associate {
            val row = it.jsonObject
            row.getValue("key").jsonPrimitive.content to row["unpriced_reason"]?.jsonPrimitive?.content
        }

    @Test
    fun `a plan command's turns with no price are on the plan, and an API-key command's have no rate card`() {
        val answer = reasons(EconomicsPayloads(UsageHeads { heads }, lookup = lookup))
        assertEquals(
            mapOf("claudex" to "plan", "claude-deepseek" to "undeclared", "claude-bonsai" to "local"),
            answer,
        )
    }

    @Test
    fun `a payload that cannot read billing gives no reason rather than a guessed one`() {
        val answer = reasons(EconomicsPayloads(UsageHeads { heads }))
        assertEquals(mapOf("claudex" to null, "claude-deepseek" to null, "claude-bonsai" to null), answer)
    }
}
