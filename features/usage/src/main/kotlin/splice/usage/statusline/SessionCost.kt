// NEW: V4-37 — one session's spend, computed rather than believed.
//
// The segment this feeds REPLACES Claude Code's `cost.total_cost_usd`, which is a per-SESSION
// number. So this must be a per-session number too: a head-wide tail total would change what the
// displayed figure means and would be wrong for two concurrent sessions on one head — which the
// operator runs constantly. Where the session is unknown the answer is null, and the statusline
// falls back to the client's own number; it is NEVER another session's, and never a head-wide sum.
// This row exists because a confidently displayed cost was wrong by 20x, so a differently-wrong
// confident number would defeat the whole exercise.
package splice.usage.statusline

import splice.core.model.HeadRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTotal
import splice.core.perf.PerfSessionTurn
import splice.usage.perf.HeadSessionPerfSource

/** What the statusline's cost segment asks for: this session's spend, or null when splice has no
 *  figure for it (StatuslineBars.costSegment decides what shows instead). */
internal fun interface SessionCostSource {
    fun usdFor(sessionId: String?, modelId: String?): Double?

    /** V4-240: whether [modelId] has a rate card on this head at all, so a head that cannot price it
     *  says so in words rather than showing a figure Claude Code priced with Anthropic's card. */
    fun rated(modelId: String?): Boolean = false

    /** V4-240 review: [usdFor] with whether it is only a LOWER BOUND, which the segment marks `≥`.
     *  [sessionStartMs] is when the client session began (its blob's `cost.total_duration_ms`
     *  before now), or null when the blob does not say. A source that cannot tell answers exact. */
    fun spendFor(sessionId: String?, modelId: String?, sessionStartMs: Long?): SessionSpend? =
        usdFor(sessionId, modelId)?.let { SessionSpend(it, lowerBound = false) }
}

/** One session's figure, and whether the true spend may be higher: a turn the head cannot price, or
 *  a session with rows from before its running total that the tail the reader holds cannot reach. */
internal data class SessionSpend(val usd: Double, val lowerBound: Boolean)

/** USD for ONE client session, from the tokens its turns already recorded against the rate card
 *  that turn's model declares.
 *
 *  [catalog] supplies the provider model entry's own rates (V4-37 stage one). [headRates] is the
 *  per-head override the amendment requires — head card first, provider entry second, null last —
 *  and it is INJECTED DATA here: the TOML field that populates it lives on HeadConfig, which stage
 *  two adds once V4-36 releases Topology.kt. The order is real and pinned today; only the
 *  declaration waits. */
internal class SessionCost(
    private val tokens: HeadSessionPerfSource,
    private val catalog: ModelCatalog? = null,
    private val headRates: HeadRates? = null,
    private val arithmetic: TokenCost = TokenCost(),
) : SessionCostSource {

    override fun usdFor(sessionId: String?, modelId: String?): Double? = spendFor(sessionId, modelId, null)?.usd

    /** V4-244: the session's running total when it holds every row of the session, which is when the
     *  session began at or after the total did (a blob with no start makes no claim either way, as
     *  with the tail). Otherwise the session has rows from before the total, and the tail prices it. */
    override fun spendFor(sessionId: String?, modelId: String?, sessionStartMs: Long?): SessionSpend? {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        val whole = tokens.sessionTotal(session)?.takeIf { sessionStartMs == null || sessionStartMs >= it.fromMs }
        return if (whole != null) spendOf(whole) else fromTail(tokens.sessionTail(session), modelId, sessionStartMs)
    }

    /** V4-244: each turn was priced at its own model's card when its row was appended; a turn with no
     *  card then makes the figure a lower bound, and no priced turn at all is no figure. */
    private fun spendOf(total: PerfSessionTotal): SessionSpend? {
        val models = total.models.values
        if (models.none { it.turns > it.unpricedTurns }) return null
        return SessionSpend(models.sumOf { it.usd }, lowerBound = models.any { it.unpricedTurns > 0 })
    }

    /** V4-240 review. Each turn is priced at the card of the model it RAN on (finding 4b), so a
     *  session that switched models is not billed at the one the status line asks about; the asked
     *  model prices only a row that recorded none. A turn whose model has no card adds nothing and
     *  makes the figure a lower bound, and so does a session that began before the oldest row of a
     *  tail the reader could not read whole (finding 4c). No priced turn at all is no figure. */
    private fun fromTail(tail: PerfSessionTail, modelId: String?, sessionStartMs: Long?): SessionSpend? {
        val asked = modelId?.let(::ratesFor)
        val turns = tail.turns.map { turn -> ratesOf(turn, asked) to bucketsOf(turn.counters) }
            .filterNot { (_, buckets) -> buckets.isEmpty }
        val priced = turns.mapNotNull { (rates, buckets) -> rates?.let { arithmetic.of(buckets, it) } }
        if (priced.isEmpty()) return null
        val tailStart = tail.tailStartMs
        val cut = tailStart != null && sessionStartMs != null && sessionStartMs < tailStart
        return SessionSpend(priced.sum(), lowerBound = priced.size < turns.size || cut)
    }

    override fun rated(modelId: String?): Boolean = modelId?.let(::ratesFor) != null

    // Both lookups key on the CANONICAL id, so a suffixed picker row ("k3[1m]") resolves the same
    // card its bare upstream id does — the same stripping the catalog does everywhere.
    private fun ratesFor(modelId: String): ModelRates? {
        val id = modelId.takeIf { it.isNotBlank() } ?: return null
        val key = catalog?.stripSuffixes(id) ?: id
        return arithmetic.ratesFor(headRates, key, providerEntry(key))
    }

    private fun providerEntry(key: String): ModelRates? {
        val c = catalog ?: return null
        return c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates
    }

    /** The card [turn] is billed at: its own model's, or [asked]'s for a row that recorded none. */
    private fun ratesOf(turn: PerfSessionTurn, asked: ModelRates?): ModelRates? {
        val model = turn.model ?: return asked
        return ratesFor(model)
    }

    /** One turn's row as billing buckets. Each perf row is ONE turn's own
     *  contribution — in_tokens is that turn's final round, out_tokens its output across rounds — so
     *  the session total is the sum of their prices, which is the same arithmetic the operator's
     *  67-turn figure was taken with. V4-240: priced turn by turn, never summed first, because a
     *  long-context tier bills one REQUEST by its own size (TokenCost). An empty turn adds nothing. */
    private fun bucketsOf(row: Map<String, Long>): TokenBuckets {
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
        // V4-85: the cache-WRITE tokens are the OTHER disjoint part of that same inclusive
        // in_tokens, and they come out of the miss bucket for exactly the reason the read does.
        // Before this they had no counter at all, so they stayed folded inside in_tokens and
        // billed at the input rate — which is a real overcharge on an Anthropic-shaped wire
        // (a write is 1.25x the input rate there, so the sign is not even consistent) and left
        // a head's declared cache_write rate as arithmetic over a permanently-zero operand.
        // ABSENT, not zero, on every row written before the counter existed: `?: 0L` then keeps
        // that row priced exactly as it was, which is the NEVER-BELOW-STATUS-QUO law — a
        // historical row cannot be retro-split into buckets it never recorded.
        //
        // coerceAtLeast(0) because a malformed or older row could carry a cached count above its
        // input count, and a negative miss bucket would SUBTRACT from the bill rather than
        // floor it.
        val rawIn = row[PerfKeys.IN_TOKENS] ?: 0L
        val cached = row[PerfKeys.CACHED_TOKENS] ?: 0L
        val written = row[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L
        return TokenBuckets(
            input = (rawIn - cached - written).coerceAtLeast(0L),
            cacheRead = cached,
            cacheWrite = written,
            output = row[PerfKeys.OUT_TOKENS] ?: 0L,
        )
    }
}
