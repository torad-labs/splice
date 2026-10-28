// NEW: "what reasoning does this turn get" — one responsibility with one output (the reasoning
// block plus its stream_options sibling), split out of ResponsesRequestBuilder.kt (2026-08-17,
// concentration campaign) as the single densest concern left in the builder. It owns the
// FIELD_EFFORT/FIELD_SUMMARY/FIELD_REASONING wire-name constants because it is the only place they
// are written. Every relocated member kept its identical name and argument list.
package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesLiteShape
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesEffort
import splice.dialect.responses.request.SUMMARY_DETAILED
import splice.dialect.responses.request.summaryFloorToDetailed
import splice.dialect.responses.stream.ResponsesLooseFields

internal class ResponsesReasoningKnobs(private val quirks: ResponsesQuirks) {

    private val effortRules = ResponsesEffort()
    private val looseFields = ResponsesLooseFields()
    private val liteShape = ResponsesLiteShape(quirks)

    internal fun resolveEffort(opts: BuildOptions): String? {
        // splice authors a Responses head's effort (Marcos, 2026-10-28: "effort at high by default, but these should
        // be configurable by users in the console and in the toml file"): the configured effort, else high. The effort
        // the client's request names (Claude Code's own picker, its thinking budget) is not read: it is Claude's
        // setting, shared with every Claude head, and it sent xhigh or max to GPT where splice.toml said high. One
        // value per head keeps a compaction on the session's effort, so the prompt-cache prefix holds (2026-09-05).
        var effort: String? =
            effortRules.normalizeEffort(opts.reasoning.effort, quirks.reasoning.effortVocabulary) ?: "high"
        if (effort == "none") return effort
        effort = effortRules.flooredForVisibility(effort, opts.reasoning.display)
        effort = effortRules.flooredForVendor(effort, quirks.reasoning.effortVocabulary)
        return effortRules.clampedForModelCeiling(
            effort,
            opts.models.upstream,
            quirks.reasoning.effortMaxRejectModelRegex,
        )
    }

    internal fun resolveSummary(raw: JsonObject, opts: BuildOptions, effort: String?): String? {
        if (!quirks.reasoning.supportsSummary || effort == null) return null
        // Operator-controlled via TOML/env/state (Knob.SUMMARY default = "detailed").
        // Precedence: request body fields > configSummary > default detailed.
        // showReasoning=off still suppresses the field (summary "none").
        if (opts.reasoning.display.isOff) return "none"
        val requested = looseFields.requestedSummary(raw)
        // v27 visibility fold (the header's "folds summary to detailed" clause — was unimplemented,
        // audit 2026-07-18): when reasoning is VISIBLE, a REQUEST-level weak summary (none/auto/
        // concise from the model/Claude Code) is floored to detailed so `summary_text` actually
        // fills. The OPERATOR's configSummary stays authoritative (a deliberate `concise` in
        // TOML/env is respected) — the fold defends against the request, not the operator.
        val folded = requested?.let { if (it in summaryFloorToDetailed) SUMMARY_DETAILED else it }
        return folded ?: effortRules.normalizeSummary(opts.reasoning.summary) ?: SUMMARY_DETAILED
    }

    /** codex-rs parity: delivery rides ONLY alongside an actual summary request. */
    internal fun summaryDeliveryOptions(reasoning: JsonObject?): JsonObject? {
        val delivery = quirks.reasoning.summaryDelivery ?: return null
        if (reasoning?.get("summary") == null) return null
        return buildJsonObject { put("reasoning_summary_delivery", delivery) }
    }

    internal fun reasoningBlock(effort: String?, summary: String?, raw: JsonObject, opts: BuildOptions): JsonObject? {
        if (effort == null) return null
        val dropSummary = quirks.reasoning.summaryRejectModelRegex?.containsMatchIn(opts.models.upstream) == true ||
            summary == null || summary == "none"
        val context = reasoningContext(raw, opts)
        return buildJsonObject {
            put(FIELD_EFFORT, effort)
            if (!dropSummary) put(FIELD_SUMMARY, summary)
            if (context != null) put(FIELD_CONTEXT, context)
        }
    }

    private fun reasoningContext(raw: JsonObject, opts: BuildOptions): String? =
        if (liteShape.isLite(opts)) {
            CONTEXT_ALL_TURNS
        } else {
            JsonScalars.str(raw[FIELD_REASONING] as? JsonObject, FIELD_CONTEXT)
                ?.takeIf { it in SUPPORTED_CONTEXTS }
        }
}

// Wire field names shared by the reasoning-block builder and ResponsesLooseFields' raw-JSON reads.
internal const val FIELD_EFFORT = "effort"
internal const val FIELD_SUMMARY = "summary"
internal const val FIELD_REASONING = "reasoning"
private const val FIELD_CONTEXT = "context"
private const val CONTEXT_ALL_TURNS = "all_turns"
private val SUPPORTED_CONTEXTS = setOf("auto", "current_turn", CONTEXT_ALL_TURNS)
