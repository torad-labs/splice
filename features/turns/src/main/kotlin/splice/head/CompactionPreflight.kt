// NEW: Oct 11, 2026 — the size refusal a compaction preflight may answer, split from HeadServer (the head's composition
// root and lifecycle) because it is a policy of its own: measured input against the serve window.
package splice.head

import kotlinx.serialization.json.JsonObject
import splice.core.model.CompactionBudgets
import splice.core.model.ModelCatalog
import splice.core.turn.TurnMeta
import splice.head.perf.PerfStats

/** The client compacts reactively only when a size refusal is HTTP 400 before SSE commits 200.
 * A refusal needs a preserved, backend-measured prefix and a text-only growth bound. Cold, rewritten
 * or media-bearing histories go upstream; bytes/3 cannot justify blocking a real conversation. */
internal class CompactionPreflight(private val catalog: ModelCatalog, private val perf: PerfStats) {
    fun refusal(meta: TurnMeta, request: JsonObject, hasPriorExchange: Boolean): Oversize? {
        val budget = CompactionBudgets.forRow(catalog, meta.route.originalModel) ?: return null
        val window = budget.serveWindow
        val estimate = perf.measuredInputs.estimate(
            meta.scope.sessionId,
            meta.scope.conversationKey,
            meta.route.upstreamModel,
            request,
        ) ?: return null
        // An ordinary continuation may compact early, so use the conservative upper bound.
        // Compact and first-exchange refusals have no recovery behind them: only measured
        // input alone beyond W proves overflow. Generation p99 is not a required minimum.
        val ordinary = hasPriorExchange && !meta.compact
        val allowance = if (ordinary) budget.totalTokens else 0L
        val bound = if (ordinary) estimate.upperTokens else estimate.lowerTokens
        val explanation = if (ordinary) {
            "estimated $bound input tokens plus $allowance reserved context tokens " +
                "exceed the $window-token window of ${meta.route.upstreamModel} " +
                "(estimate basis ${estimate.basis}, compaction generation p99 ${budget.generationTokens})"
        } else {
            val request = if (meta.compact) "This compaction" else "This first request"
            "at least $bound input tokens exceed the $window-token window of ${meta.route.upstreamModel}. " +
                "$request cannot fit here. Resume on a model with a larger context window, " +
                "or start a fresh conversation."
        }
        return if (bound > window - allowance) Oversize("prompt is too long: $explanation", window) else null
    }
}

/** A request the preflight refuses: [detail] is the log's account of the bound, and [windowTokens] is the model's
 *  window, which the client's line names (SpliceLines). */
internal data class Oversize(val detail: String, val windowTokens: Long)
