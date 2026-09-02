// PORT-OF: server/src/codex/translate-request.mjs + grok/translate-request.mjs @ pre-public-port-baseline —
// ONE Responses request builder parameterized by Quirks (the two Node files are ~90% identical;
// the v29 lesson: copies drift). Invariants:
//   - PURE: {req, meta} from (body, opts); never mutates the body (meta replaced v29's
//     body.__claudex* magic props);
//   - full fidelity on normal turns: never shrink input, never swap the model;
//   - COMPACT turns: tools stripped (a tooled compaction can answer with tool_use and empty
//     the text channel — v29 worst case), forced text-only instructions, model ALWAYS the
//     session's own (body.model — no compact-model override exists), and effort INHERITS THE
//     SESSION (codex quirk; a mismatch on model OR effort invalidates the whole prompt-cache
//     prefix — the "compaction ate my subscription" bug), unless the quirk pins one (grok: low);
//   - images ride as input_image (base64 data URL or url); documents become honest markers;
//     images inside tool_result ride in a follow-up user message (function_call_output is
//     string-only on these backends — v25: screenshots silently vanished);
//   - include vs replay are SEPARATE (Grok Build / Node measured lesson):
//       includeEncryptedReasoning → ask the server to RETURN encrypted_content (opaque handle)
//       replayReasoning → inject prior redacted_thinking into the NEXT input
//     Default for deep thinking: include ON when reasoning is shown, replay OFF (replaying
//     prior opaque encrypted reasoning items make the model reuse thin thinking instead of re-deriving).
//   - cache key: first-message-hash 'splice-<sha256(first user text)[:32]>' (codex — stable
//     across per-turn system-reminder drift) or session-id 'claude-grok:<sid>' (grok);
//   - effort precedence (v27): explicit body fields > /effort picker (thinking.budget_tokens)
//     > config/env fallback > high; visibility floor when showReasoning != off never RAISES
//     a deliberate pick, only floors none/minimal -> low and folds summary to detailed;
//   - spark rejects reasoning.summary (openai/codex#31846) — omitted via quirk regex;
//   - gpt-5.4-mini caps effort at xhigh — the backend 400s effort=max, clamped via quirk regex;
//   - ChatGPT backend rejects token-limit params: max_output_tokens is NEVER sent; the clamp
//     applies to REPORTED usage (P3-USE).
//
// Decomposed 2026-08-17 (concentration campaign) into sibling files in this same package —
// ResponsesQuirks.kt, ResponsesBuildContract.kt, ResponsesRequestAssembler.kt, ResponsesToolPlan.kt,
// ResponsesReasoningKnobs.kt, ResponsesLooseFields.kt, ResponsesEffort.kt, ResponsesStableIds.kt,
// ResponsesInputBuilder.kt, ResponsesInputParts.kt, ResponsesInputTools.kt, ResponsesReasoningInject.kt.
// This residual stays the single entry point and keeps this filename for ArchitectureLawsTest:126
// and the CX-02 wall. Every relocated member kept its identical name.

package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import splice.core.turn.CompactInstructions
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest

public class ResponsesRequestBuilder(private val quirks: ResponsesQuirks) {

    // Collaborators that used to be file-level functions. Each is bound to THIS builder's quirks
    // where it needs them, so every relocated member kept its original argument list.
    private val toolPlan = ResponsesToolPlan(quirks)
    private val knobs = ResponsesReasoningKnobs(quirks)
    private val looseFields = ResponsesLooseFields(quirks)
    private val ids = ResponsesStableIds()
    private val assembler = ResponsesRequestAssembler(quirks)

    public fun build(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): BuiltRequest {
        // Partition FIRST, before the message walk: it is a pure function of (body.tools, policy)
        // ONLY (ToolSurface.kt header) and the declaration-replay injection below needs to know,
        // per ToolUseBlock, whether that tool is THIS request's deferred set — moving it earlier
        // costs nothing (buildRequestObject no longer recomputes it) and keeps position 0 of the
        // wire input untouched by anything the walk discovers.
        val partition = toolPlan.partition(body, opts)
        val declareByName = toolPlan.declarationCandidates(body, partition)
        // The loop guard walks the same conversation (stateless) and marks identical-failed-call
        // streaks for a directive in that result's output. Compaction turns are excluded: their
        // results fold to plain text and a directive would only feed the summarizer noise.
        val loopGuardDirectives =
            if (quirks.loopGuard && !opts.compact) LoopGuard.analyze(body.messages) else emptyMap()
        // Constructed per build (the field version predates per-build state) — cheap, race-free.
        val inputBuilder = ResponsesInputBuilder(quirks, loopGuardDirectives)
        val input = buildJsonArray {
            for (msg in body.messages) {
                inputBuilder.appendMessage(this, msg, opts, declareByName)
            }
        }
        val instructions = compactAwareInstructions(body.system, opts.compact)
        val effort = knobs.resolveEffort(body, raw, opts)
        val summary = knobs.resolveSummary(raw, opts, effort)
        val reasoning = knobs.reasoningBlock(effort, summary, opts)

        val built = assembler.buildRequestObject(body, opts, RequestParts(input, instructions, reasoning, partition))
        // meta.summary reflects what was ACTUALLY sent (spark drops it → "none"), like Node's
        // `req.reasoning?.summary ?? 'none'` — not the computed-but-maybe-dropped value.
        val sentSummary = looseFields.sentSummary(reasoning)

        val meta = TurnMeta(
            compact = opts.compact,
            showReasoning = opts.showReasoning,
            stream = body.stream,
            originalModel = opts.originalModel,
            upstreamModel = opts.upstreamModel,
            clientMaxTokens = body.maxTokens?.takeIf { it > 0 },
            effort = effort ?: "disabled",
            summary = sentSummary,
            budgetTokens = body.thinking?.budgetTokens,
            // The reasoning cache's conversation scope — the SAME derivation the provider's
            // lookup closure uses, so capture (which only sees TurnMeta) and injection agree.
            conversationKey = ids.stablePromptCacheKey(body),
            sessionId = opts.sessionId,
            // summaryParts is NOT passed: TurnMeta's default constructs the turn's one instance.
            // Every continuation round reuses this meta object (continuationRequest bypasses
            // build()), so the dedup state it carries is genuinely turn-scoped.
            toolsEager = built.toolsEager,
            toolsDeferred = built.toolsDeferred,
        )
        return BuiltRequest(built.req, meta, built.toolSearch)
    }

    // CX-02: the directive text moved to :core (withCompactDirective) so chat and passthrough emit
    // the SAME one. The composition is unchanged — the Node .filter(Boolean) that dropped the ""
    // separator is what withCompactDirective's filter reproduces — so the wire bytes are identical.
    private fun compactAwareInstructions(system: String?, compact: Boolean): String {
        if (!compact) return system.orEmpty().ifEmpty { "You are a helpful assistant." }
        return CompactInstructions.withCompactDirective(system, compact = true)
    }
}
