// NEW: the hourly token-economics rollup projection (quota instrument), SUMS ONLY: what each hour cost, how many
// turns it served and how many ran on a plan. Sums stay mergeable when the window changes. The store keeps more sums
// per hour (tokens, bytes, tools); the route serves only what the Accounts page reads (Oct 12, splice-lead's sweep).
//
// A head whose rollup cannot be shown honestly answers with no buckets and an `unavailable` sentence, and the
// other heads still answer: one head's evidence never takes the whole route down.
//
// A SIBLING of PerfPayloads rather than a method on it: perf answers "where did the latency go"
// from a bounded tail, economics answers "what has this cost against the plan" from week-wide sums,
// and the two share no input, no reader and no window.
package splice.usage.economics

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.wire.ControlFields.HEADS
import splice.core.wire.ControlFields.KEY
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeads
import splice.usage.UsageReadPreparation
import splice.usage.perf.HeadPriceGap

public class EconomicsPayloads(
    private val heads: UsageHeads,
    /** Where each command's billing is read, for the reason its turns with no price have none. */
    private val lookup: UsageHeadLookup? = null,
) {

    public fun economicsJson(): String = economicsJson(null)

    public fun economicsJson(preparation: UsageReadPreparation?): String = buildJsonObject {
        putJsonArray(HEADS) {
            heads.all().forEach { m ->
                addJsonObject {
                    put(KEY, m.key)
                    HeadPriceGap.wire(lookup, m.key)?.let { put(UNPRICED_REASON, it) }
                    put("all_accounts_on_plan", HeadPriceGap.coversEveryAccount(lookup, m))
                    if (preparation?.economicsReady(m) == false) {
                        buckets(this, emptyList())
                        put("read_pending", true)
                        put("unavailable", "Reading saved request history before showing this command's hourly totals.")
                    } else {
                        when (val read = m.sinks.economics?.read() ?: EconomicsRead.Rows(emptyList())) {
                            is EconomicsRead.Rows -> {
                                buckets(this, read.rows)
                                putJsonArray("not_read_hours") { read.notRead.forEach { add(it) } }
                            }
                            is EconomicsRead.Unavailable -> {
                                buckets(this, emptyList())
                                put("unavailable", read.reason)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun buckets(head: JsonObjectBuilder, rows: List<EconomicsRow>) = head.putJsonArray("buckets") {
        rows.forEach { b ->
            addJsonObject {
                put("hour", b.hour)
                put("turns", b.turns)
                // unread, kept: a count of turns the provider refused, the one failure figure an hour carries
                put("rate_limited", b.rateLimited)
                // V4-221: null is "not priced then" (an hour from before the field).
                put("cost_usd", b.cost.costUsd)
                put("unpriced_turns", b.cost.unpricedTurns)
                // Oct 12: null is an hour from before the field; failed requests with no usage are counted here, not above.
                put("failed_turns", b.cost.failedTurns)
                // Oct 11: null on both is an hour never split, whose figures above mix plan and key requests.
                put("plan_turns", b.cost.planTurns)
                put("plan_value_usd", b.cost.planValueUsd)
            }
        }
    }
}

// why: the word, "plan", "local" or "undeclared", for why a command's turns with no price have none; the Day row
// says "on your plan" for a plan's and "with no price" for the rest (Oct 10, 2026).
internal const val UNPRICED_REASON = "unpriced_reason"
