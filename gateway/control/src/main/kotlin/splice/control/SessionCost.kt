// NEW: V4-37 — one session's spend, computed rather than believed.
//
// The segment this feeds REPLACES Claude Code's `cost.total_cost_usd`, which is a per-SESSION
// number. So this must be a per-session number too: a head-wide tail total would change what the
// displayed figure means and would be wrong for two concurrent sessions on one head — which the
// operator runs constantly. Where the session is unknown the answer is null, and the statusline
// falls back to the client's own number; it is NEVER another session's, and never a head-wide sum.
// This row exists because a confidently displayed cost was wrong by 20x, so a differently-wrong
// confident number would defeat the whole exercise.
package splice.control

import splice.core.model.HeadRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.PerfKeys

/** What the statusline's cost segment asks for: this session's spend, or null to render the
 *  client's own number exactly as before. */
public fun interface SessionCostSource {
    public fun usdFor(sessionId: String?, modelId: String?): Double?
}

/** USD for ONE client session, from the tokens its turns already recorded against the rate card
 *  that turn's model declares.
 *
 *  [catalog] supplies the provider model entry's own rates (V4-37 stage one). [headRates] is the
 *  per-head override the amendment requires — head card first, provider entry second, null last —
 *  and it is INJECTED DATA here: the TOML field that populates it lives on HeadConfig, which stage
 *  two adds once V4-36 releases Topology.kt. The order is real and pinned today; only the
 *  declaration waits. */
public class SessionCost(
    private val tokens: HeadSessionPerfSource,
    private val catalog: ModelCatalog? = null,
    private val headRates: HeadRates? = null,
    private val arithmetic: TokenCost = TokenCost(),
) : SessionCostSource {

    override fun usdFor(sessionId: String?, modelId: String?): Double? {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        val id = modelId?.takeIf { it.isNotBlank() } ?: return null
        return priced(session, id)
    }

    private fun priced(session: String, id: String): Double? {
        // Both lookups key on the CANONICAL id, so a suffixed picker row ("k3[1m]") resolves the
        // same card its bare upstream id does — the same stripping the catalog does everywhere.
        val key = catalog?.stripSuffixes(id) ?: id
        val rates = arithmetic.ratesFor(headRates, key, providerEntry(key)) ?: return null
        return bucketsFor(session).takeIf { !it.isEmpty }?.let { arithmetic.of(it, rates) }
    }

    private fun providerEntry(key: String): ModelRates? {
        val c = catalog ?: return null
        return c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates
    }

    /** Sums the three billing buckets over this session's rows. Each perf row is ONE turn's own
     *  contribution — in_tokens is that turn's final round, out_tokens its output across rounds — so
     *  the session total is their sum, which is the same arithmetic the operator's 67-turn figure
     *  was taken with. */
    private fun bucketsFor(sessionId: String): TokenBuckets {
        var input = 0L
        var cacheRead = 0L
        var output = 0L
        for (row in tokens.tailNumericFor(sessionId)) {
            // V4-37 redo: IN_TOKENS is INCLUSIVE of the cached portion, so the cache-miss bucket is
            // the difference, never the raw field. Both dialects that write it agree, and by
            // construction rather than by vendor luck: ChatUsage sets inputTokens from
            // prompt_tokens, which the vendor defines as inclusive, and PassthroughUsage.kt:23 spells
            // it out — inputTokens = inputTokens + cacheRead + cacheCreation. TurnUsageStamp.kt:48
            // and :50 then write both counters straight from that same object with NO subtraction,
            // so billing the raw field as a miss AND cached_tokens as a read charges the cached
            // portion twice, once at the miss rate. On the deepseek session that measured 46.47
            // dollars against a true 1.42.
            //
            // DO NOT BE MISLED BY ChatUsage's own comment that HeadServer disjoints them. That is
            // true of the CLIENT usage envelope (TurnCacheLine.kt:21 writes
            // input_tokens_details.cached_tokens), which is a DIFFERENT surface from this perf row.
            // PassthroughStreamTranslator.kt:14 states the expectation directly — cachedTokens =
            // cache_read, "making the downstream subtraction reproduce the disjoint numbers" — so
            // this subtraction is the consumer doing what the producer documented.
            //
            // coerceAtLeast(0) because a malformed or older row could carry a cached count above its
            // input count, and a negative miss bucket would SUBTRACT from the bill rather than
            // floor it.
            val rawIn = row[PerfKeys.IN_TOKENS] ?: 0L
            val cached = row[PerfKeys.CACHED_TOKENS] ?: 0L
            input += (rawIn - cached).coerceAtLeast(0L)
            cacheRead += cached
            output += row[PerfKeys.OUT_TOKENS] ?: 0L
        }
        return TokenBuckets(input = input, cacheRead = cacheRead, output = output)
    }
}
