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

import kotlinx.serialization.json.JsonObject
import splice.core.model.HeadRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TokenCost
import splice.core.model.TurnBill
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTotal
import splice.core.perf.PerfSessionTurn
import splice.usage.perf.HeadPerfSkipSource
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
        if (models.none { it.turns > it.gaps.unpricedTurns }) return null
        val incomplete = models.any { it.gaps.unpricedTurns > 0 || it.gaps.unreportedUsageTurns > 0 }
        return SessionSpend(models.sumOf { it.usd }, lowerBound = incomplete)
    }

    /** V4-240 review. Each turn is priced at the card of the model it RAN on (finding 4b), so a
     *  session that switched models is not billed at the one the status line asks about; the asked
     *  model prices only a row that recorded none. A turn whose model has no card adds nothing and
     *  makes the figure a lower bound, and so does a session that began before the oldest row of a
     *  tail the reader could not read whole (finding 4c). No priced turn at all is no figure. */
    private fun fromTail(tail: PerfSessionTail, modelId: String?, sessionStartMs: Long?): SessionSpend? {
        val asked = modelId?.let(::ratesFor)
        val turns = tail.turns.map { turn -> ratesOf(turn, asked) to turn.counters }
            .filter { (_, row) -> TurnBill.isCounted(row) }
        val priced = turns.mapNotNull { (rates, row) -> TurnBill.usd(row, rates, arithmetic) }
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
        val c = catalog?.live() ?: return null
        return c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates
    }

    /** The card [turn] is billed at: its own model's, or [asked]'s for a row that recorded none. */
    private fun ratesOf(turn: PerfSessionTurn, asked: ModelRates?): ModelRates? {
        val model = turn.model ?: return asked
        return ratesFor(model)
    }
}

/**
 * What the cost segment needs to know about the head.
 *
 * [sessionCost] is V4-37: this session's spend from the head's own token counts against rates
 * declared in TOML, instead of the client's `total_cost_usd` (priced with an Anthropic card, a
 * measured ~20x high on every non-Anthropic head). Null = render the client's number.
 *
 * [perfSkips] is V4-45: how many perf rows the COST reader had to drop. A source rather than a
 * number, deliberately: RendererCacheTest pins that the renderer is cached per head and built once,
 * so a captured count would freeze at the head's start and the operator would never learn that the
 * figure beside it had gone short.
 *
 * [anthropicUpstream] is V4-240: the head forwards the client's own login, so Claude Code's figure is
 * priced at this upstream's card and may stand in for splice's. False on every other head, where a
 * model splice cannot price says "no rate card" instead.
 */
internal class StatuslineSpend(
    private val sessionCost: SessionCostSource? = null,
    private val perfSkips: HeadPerfSkipSource? = null,
    private val anthropicUpstream: Boolean = false,
) {
    /** The cost segment for one tick; [sessionStartMs] is when the client session began, or null. */
    fun segment(
        bars: StatuslineBars,
        root: JsonObject,
        sessionId: String?,
        modelId: String?,
        sessionStartMs: Long?,
    ): String? {
        val spend = sessionCost?.spendFor(sessionId, modelId, sessionStartMs)
        return bars.costSegment(
            root,
            spend?.usd,
            perfSkips?.skippedRowCount() ?: 0L,
            CostFallback(rated = sessionCost?.rated(modelId) ?: false, clientPriced = anthropicUpstream),
            lowerBound = spend?.lowerBound == true,
        )
    }
}
