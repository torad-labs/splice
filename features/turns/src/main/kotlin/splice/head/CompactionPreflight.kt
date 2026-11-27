// NEW: Oct 11, 2026 — the size refusal a compaction preflight may answer, split from HeadServer (the head's composition
// root and lifecycle) because it is a policy of its own: measured input against the serve window.
package splice.head

import kotlinx.serialization.json.JsonObject
import splice.core.failure.CompactionFit
import splice.core.failure.RequestFacts
import splice.core.model.CompactionBudget
import splice.core.model.CompactionBudgets
import splice.core.model.ModelCatalog
import splice.core.model.ModelServeWindows
import splice.core.turn.TurnMeta
import splice.head.perf.InputEstimate
import splice.head.perf.PerfStats

/** The client compacts reactively only when a size refusal is HTTP 400 before SSE commits 200.
 * A refusal needs a preserved, backend-measured prefix and a text-only growth bound. Cold, rewritten
 * or media-bearing histories go upstream; bytes/3 cannot justify blocking a real conversation. */
internal class CompactionPreflight(private val catalog: ModelCatalog, private val perf: PerfStats) {
    fun refusal(meta: TurnMeta, request: JsonObject, hasPriorExchange: Boolean): Oversize? {
        val target = refusalTarget(meta) ?: return null
        val estimate = perf.measuredInputs.estimate(
            meta.scope.sessionId,
            meta.scope.conversationKey,
            meta.route.upstreamModel,
            request,
        ) ?: return null
        return oversizeOrNull(meta, target, estimate, hasPriorExchange)
    }

    /** The provider may refuse a compaction admitted here. Keep the facts its refusal can use to size a retry. */
    fun requestFacts(meta: TurnMeta, hasPriorExchange: Boolean, otherLogin: Boolean): RequestFacts = RequestFacts(
        meta.compact,
        firstExchange = !hasPriorExchange,
        otherLogin = otherLogin,
        compactionFit = if (hasPriorExchange) fit(meta) else null,
    )

    private fun fit(meta: TurnMeta): CompactionFit? {
        val input = meta.compaction.inputEstimateTokens ?: return null
        val route = meta.route
        val window = ModelServeWindows.forRow(catalog, route.originalModel)
        val summary = route.clientMaxTokens
            ?: CompactionBudgets.forRow(catalog, route.originalModel)?.generationTokens
            ?: 0
        return CompactionFit(input, window, summary)
    }

    /** A target can trigger compaction early, but it is not proof that the summarizer cannot fit the input. */
    private fun refusalTarget(meta: TurnMeta): RefusalTarget? {
        val current = catalog.live()
        val model = meta.route.originalModel
        val budget = CompactionBudgets.forRow(current, model) ?: return null
        val window = if (meta.compact) {
            ModelServeWindows.knownCeilingFor(current, model) ?: return null
        } else {
            budget.serveWindow
        }
        return RefusalTarget(budget, window)
    }

    private fun oversizeOrNull(
        meta: TurnMeta,
        target: RefusalTarget,
        estimate: InputEstimate,
        hasPriorExchange: Boolean,
    ): Oversize? {
        // An ordinary continuation may compact early, so use the conservative upper bound.
        // Compact and first-exchange admission still require proof: only measured input alone
        // beyond W proves overflow. Room for the summary matters to the retry, not to admission.
        val ordinary = hasPriorExchange && !meta.compact
        val allowance = if (ordinary) target.budget.totalTokens else 0L
        val bound = if (ordinary) estimate.upperTokens else estimate.lowerTokens
        if (bound <= target.window - allowance) return null
        val explanation = if (ordinary) {
            explainOrdinary(meta.route.upstreamModel, target, estimate)
        } else {
            "at least $bound input tokens exceed the ${target.window}-token window of ${meta.route.upstreamModel}"
        }
        return Oversize("prompt is too long: $explanation", target.window, bound, target.budget.generationTokens)
    }

    private fun explainOrdinary(
        upstreamModel: String,
        target: RefusalTarget,
        estimate: InputEstimate,
    ): String = "estimated ${estimate.upperTokens} input tokens plus ${target.budget.totalTokens} reserved " +
        "context tokens exceed the ${target.window}-token window of $upstreamModel " +
        "(estimate basis ${estimate.basis}, compaction generation p99 ${target.budget.generationTokens})"
}

private data class RefusalTarget(val budget: CompactionBudget, val window: Long)

/** A request the preflight refuses: [detail] is the log's account of the bound, and [windowTokens] is the model's
 *  window, which the client's line names (SpliceLines). */
internal data class Oversize(
    val detail: String,
    val windowTokens: Long,
    val inputTokens: Long,
    val generationTokens: Long,
)
