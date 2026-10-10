// NEW: the hourly token-economics rollup projection (quota instrument), SUMS ONLY. Every ratio the
// dashboard renders — hit rate, read amplification, deferral savings, envelope cost, the exhaustion
// projection — is derived from these at render time. Deriving on the wire would freeze a rounding
// choice into the contract and make the numbers un-recomputable when the window changes; sums stay
// mergeable and honest.
//
// `ceiling_tokens` is the PROVIDER's own limit when it sends one (x-ratelimit-limit-tokens); null
// where it does not, and a null ceiling must render as "no ceiling known", never as a guess — an
// invented denominator is how a quota gauge lies.
//
// A head whose rollup cannot be shown honestly answers with no buckets and an `unavailable` sentence, and the
// other heads still answer: one head's evidence never takes the whole route down.
//
// A SIBLING of PerfPayloads rather than a method on it: perf answers "where did the latency go"
// from a bounded tail, economics answers "what has this cost against the plan" from week-wide sums,
// and the two share no input, no reader and no window.
package splice.usage.economics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.perf.ECONOMICS_RETENTION_HOURS
import splice.core.util.WallClock
import splice.core.wire.ControlFields.HEADS
import splice.core.wire.ControlFields.KEY
import splice.core.wire.ControlFields.LABEL
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeads
import splice.usage.UsageReadPreparation
import splice.usage.perf.HeadPriceGap

// V4-122: this was 192 with a comment claiming it mirrored EconomicsStore's RETENTION_MS — an
// equality asserted in PROSE, which nothing enforced and which :daemon-control could not import even if it
// wanted to, having no dependency edge to :daemon-head. Both spellings of the window now come from
// splice.core.perf, which is the lowest module both reach.

public class EconomicsPayloads(
    private val heads: UsageHeads,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** Where each command's billing is read, for the reason its turns with no price have none. */
    private val lookup: UsageHeadLookup? = null,
) {

    public fun economicsJson(): String = economicsJson(null)

    public fun economicsJson(preparation: UsageReadPreparation?): String = buildJsonObject {
        put("retention_hours", ECONOMICS_RETENTION_HOURS)
        put("generated_at", clock())
        putJsonArray(HEADS) {
            heads.all().forEach { m ->
                addJsonObject {
                    put(KEY, m.key)
                    put(LABEL, m.label)
                    HeadPriceGap.wire(lookup, m.key)?.let { put(UNPRICED_REASON, it) }
                    val ceiling = m.usage.snapshot().ratelimit?.limitTokens
                    if (ceiling == null) put("ceiling_tokens", JsonNull) else put("ceiling_tokens", ceiling)
                    if (preparation?.economicsReady(m) == false) {
                        buckets(this, emptyList())
                        put("read_pending", true)
                        put("unavailable", "Reading saved request history before showing this command's hourly totals.")
                    } else {
                        when (val read = m.sinks.economics?.read() ?: EconomicsRead.Rows(emptyList())) {
                            is EconomicsRead.Rows -> buckets(this, read.rows)
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
                put("local_steps", b.localSteps)
                put("unreported_usage_turns", b.counts.unreportedUsageTurns)
                put("in_tokens", b.tokens.inTokens)
                put("cached_tokens", b.tokens.cachedTokens)
                put("cache_write_tokens", b.tokens.cacheWriteTokens)
                put("out_tokens", b.tokens.outTokens)
                put("req_bytes", b.bytes.reqBytes)
                put("upstream_req_bytes", b.bytes.upstreamBytes)
                put("tools_eager", b.tools.toolsEager)
                put("tools_deferred", b.tools.toolsDeferred)
                put("deferral_turns", b.tools.deferralTurns)
                put("rate_limited", b.rateLimited)
                // V4-221: null is "not priced then" (an hour from before the field).
                put("cost_usd", b.cost.costUsd)
                put("unpriced_turns", b.cost.unpricedTurns)
            }
        }
    }
}

// why: the word, "plan", "local" or "undeclared", for why a command's turns with no price have none; the Day row
// says "on your plan" for a plan's and "with no price" for the rest (Oct 10, 2026).
internal const val UNPRICED_REASON = "unpriced_reason"
