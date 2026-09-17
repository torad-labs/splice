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
// A SIBLING of PerfPayloads rather than a method on it: perf answers "where did the latency go"
// from a bounded tail, economics answers "what has this cost against the plan" from week-wide sums,
// and the two share no input, no reader and no window.
package splice.control.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.control.ManagedHead
import splice.core.util.WallClock

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"

// Mirrors EconomicsStore's RETENTION_MS (8 days) — the UI needs the window it is reading over to
// size the week bar honestly when the buckets do not yet fill it.
private const val ECONOMICS_RETENTION_HOURS = 192

internal class EconomicsPayloads(
    private val heads: Map<String, ManagedHead>,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {

    fun economicsJson(): String = buildJsonObject {
        put("retention_hours", ECONOMICS_RETENTION_HOURS)
        put("generated_at", clock())
        putJsonArray(HEADS) {
            heads.values.forEach { m ->
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    val ceiling = m.usage.snapshot().ratelimit?.limitTokens
                    if (ceiling == null) put("ceiling_tokens", JsonNull) else put("ceiling_tokens", ceiling)
                    putJsonArray("buckets") {
                        m.economics?.buckets().orEmpty().forEach { b ->
                            addJsonObject {
                                put("hour", b.hour)
                                put("turns", b.turns)
                                put("in_tokens", b.inTokens)
                                put("cached_tokens", b.cachedTokens)
                                put("out_tokens", b.outTokens)
                                put("req_bytes", b.reqBytes)
                                put("upstream_req_bytes", b.upstreamBytes)
                                put("tools_eager", b.toolsEager)
                                put("tools_deferred", b.toolsDeferred)
                                put("deferral_turns", b.deferralTurns)
                                put("rate_limited", b.rateLimited)
                            }
                        }
                    }
                }
            }
        }
    }.toString()
}
