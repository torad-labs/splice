// NEW: "what reasoning does this turn get" — one responsibility with one output (the reasoning
// block plus its stream_options sibling), split out of ResponsesRequestBuilder.kt (2026-08-17,
// concentration campaign) as the single densest concern left in the builder. It owns the
// FIELD_EFFORT/FIELD_SUMMARY/FIELD_REASONING wire-name constants because it is the only place they
// are written. Every relocated member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest

internal class ResponsesReasoningKnobs(private val quirks: ResponsesQuirks) {

    private val effortRules = ResponsesEffort()
    private val looseFields = ResponsesLooseFields(quirks)
    private val liteShape = ResponsesLiteShape(quirks)

    internal fun resolveEffort(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): String? {
        // No compaction pin, on any provider (2026-09-05): a compaction inherits the session's
        // effort or the reasoning mismatch invalidates the whole prompt-cache prefix.
        if (body.thinking?.disabled == true && quirks.effortLadder == EffortLadder.CODEX) return null
        var effort = looseFields.looseEffort(raw)
        if (effort == null) {
            // v27: the /effort picker (budget) WINS over the config/env fallback
            val budgetEffort = body.thinking
                ?.takeIf { !it.disabled }
                ?.budgetTokens
                ?.let { effortRules.effortFromBudget(it, quirks.effortLadder) }
            effort = budgetEffort ?: effortRules.normalizeEffort(opts.configEffort, quirks.effortLadder) ?: "high"
        }
        effort = effortRules.flooredForVisibility(effort, opts.showReasoning)
        effort = effortRules.flooredForGrok(effort, quirks.effortLadder)
        return effortRules.clampedForModelCeiling(effort, opts.upstreamModel, quirks.effortMaxRejectModelRegex)
    }

    internal fun resolveSummary(raw: JsonObject, opts: BuildOptions, effort: String?): String? {
        if (!quirks.supportsSummary || effort == null) return null
        // Operator-controlled via TOML/env/state (Knob.SUMMARY default = "detailed").
        // Precedence: request body fields > configSummary > default detailed.
        // showReasoning=off still suppresses the field (summary "none").
        if (opts.showReasoning.isOff) return "none"
        val requested = looseFields.requestedSummary(raw)
        // v27 visibility fold (the header's "folds summary to detailed" clause — was unimplemented,
        // audit 2026-07-18): when reasoning is VISIBLE, a REQUEST-level weak summary (none/auto/
        // concise from the model/Claude Code) is floored to detailed so `summary_text` actually
        // fills. The OPERATOR's configSummary stays authoritative (a deliberate `concise` in
        // TOML/env is respected) — the fold defends against the request, not the operator.
        val folded = requested?.let { if (it in summaryFloorToDetailed) SUMMARY_DETAILED else it }
        return folded ?: effortRules.normalizeSummary(opts.configSummary) ?: SUMMARY_DETAILED
    }

    /** codex-rs parity: delivery rides ONLY alongside an actual summary request. */
    internal fun summaryDeliveryOptions(reasoning: JsonObject?): JsonObject? {
        val delivery = quirks.summaryDelivery ?: return null
        if (reasoning?.get("summary") == null) return null
        return buildJsonObject { put("reasoning_summary_delivery", delivery) }
    }

    internal fun reasoningBlock(effort: String?, summary: String?, opts: BuildOptions): JsonObject? {
        if (effort == null) return null
        val dropSummary = quirks.summaryRejectModelRegex?.containsMatchIn(opts.upstreamModel) == true ||
            summary == null || summary == "none"
        return buildJsonObject {
            put(FIELD_EFFORT, effort)
            if (!dropSummary) put(FIELD_SUMMARY, summary)
            // codex-rs lite parity: reasoning context spans the session, not just the current turn.
            if (liteShape.isLite(opts)) put("context", "all_turns")
        }
    }
}

// Wire field names shared by the reasoning-block builder and ResponsesLooseFields' raw-JSON reads.
internal const val FIELD_EFFORT = "effort"
internal const val FIELD_SUMMARY = "summary"
internal const val FIELD_REASONING = "reasoning"
