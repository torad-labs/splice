// NEW: what one reply costs, for a surface that sent a model a question itself (Compare models) and read the token
// totals off the answer. It is the decision a recorded request gets (TurnPriceGap), asked of a row built from those
// totals, so a reply there and the same request on the Requests page can never read two different prices.
package splice.usage.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.TurnPrice
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup

/** The reply the account on [head] was asked for: the model, who answered, and the tokens it reported. */
internal data class RepliedTokens(val model: String?, val account: String?, val tokensIn: Long, val tokensOut: Long)

internal class ReplyPrice(private val heads: UsageHeadLookup) {
    /** The row's own three price fields: `cost_usd` on a key, `plan_value_usd` on a plan, else `cost_reason`. */
    fun json(head: UsageHead, reply: RepliedTokens, at: Long): JsonObject {
        val gaps = TurnPriceGap(head.statusline.catalog?.let(::TurnPrice), AccountPlans(head, heads.billing(head.key)))
        val row = PerfRow(
            ts = at,
            outcome = OutcomeTag.OK.wire,
            fields = mapOf(
                PerfKeys.IN_TOKENS to reply.tokensIn,
                PerfKeys.CACHED_TOKENS to 0L,
                PerfKeys.CACHE_WRITE_TOKENS to 0L,
                PerfKeys.OUT_TOKENS to reply.tokensOut,
            ),
            facts = PerfTurnFacts(model = reply.model, account = reply.account),
        )
        return buildJsonObject {
            put("cost_usd", gaps.usd(row))
            put("cost_reason", gaps.of(row)?.wire)
            put("plan_value_usd", gaps.value(row).takeIf { gaps.onPlan(row) })
        }
    }
}
