// PORT-OF: server/src/codex/translate-request.mjs + grok/translate-request.mjs @ pre-public-port-baseline —
// ONE Responses request builder parameterized by Quirks (the two Node files are ~90% identical;
// the v29 lesson: copies drift). Invariants:
//   - PURE: {req, meta} from (body, opts); never mutates the body (meta replaced v29's
//     body.__claudex* magic props);
//   - full fidelity on normal turns: never shrink input, never swap the model;
//   - COMPACT turns are built EXACTLY like any other turn (2026-09-05, operator law): same
//     instructions, same tools and partition, same history rendering, same lite shape, same
//     reasoning items, same model, same effort. The backend's prompt cache is an exact-prefix
//     match, and every compact-only reshaping this builder used to do (directive appended to
//     instructions, tools stripped, tool results folded to user text, images dropped, lite off,
//     cached reasoning not reinjected) moved the prefix from token zero — so every compaction,
//     the most expensive turn class there is, read the whole transcript cold (perf rows: compact
//     cached_tokens=0 on every model). `opts.compact` is therefore NOT consulted here; it only
//     reaches TurnMeta for the response side (watchdog budget, empty-compaction gate, telemetry).
//   - images ride as input_image and documents as input_file where the backend supports them;
//     mixed function outputs use ordered public content parts where supported, with profile-specific
//     fallbacks for endpoints that only accept text;
//   - include vs replay are SEPARATE (Grok Build / Node measured lesson):
//       includeEncryptedReasoning → ask the server to RETURN encrypted_content (opaque handle)
//       replayReasoning → inject prior redacted_thinking into the NEXT input
//     Default for deep thinking: include ON when reasoning is shown, replay OFF (replaying
//     prior opaque encrypted reasoning items make the model reuse thin thinking instead of re-deriving).
//   - cache key: first-message-hash 'splice-<sha256(first user text)[:32]>' (codex — stable
//     across per-turn system-reminder drift) or session-id 'claude-grok:<sid>' (grok);
//   - effort precedence (v27): explicit body fields > /effort picker (thinking.budget_tokens)
//     > config/env fallback > high; visibility floor when showReasoning != off never RAISES
//     a deliberate none pick; it floors minimal -> low and folds summary to detailed;
//   - spark rejects reasoning.summary (openai/codex#31846) — omitted via quirk regex;
//   - gpt-5.4-mini caps effort at xhigh — the backend 400s effort=max, clamped via quirk regex;
//   - max_tokens maps to the public output limit when the endpoint profile enables it. A provider's
//     non-enforcement policy and limitations remain explicit rather than a dialect-wide default.
//
// Decomposed 2026-08-17 (concentration campaign) into sibling files in this same package —
// ResponsesQuirks.kt, ResponsesBuildContract.kt, ResponsesRequestAssembler.kt, ResponsesToolPlan.kt,
// ResponsesReasoningKnobs.kt, ResponsesLooseFields.kt, ResponsesEffort.kt, ResponsesStableIds.kt,
// ResponsesInputBuilder.kt, ResponsesInputParts.kt, ResponsesInputTools.kt, ResponsesReasoningInject.kt.
// This residual stays the single entry point and keeps this filename for ArchitectureLawsTest:126
// and the CX-02 wall. Every relocated member kept its identical name.

package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.turn.TurnToolSurface
import splice.core.wire.AnthropicRequest
import splice.dialect.responses.ResponsesLiteShape
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ResponsesReasoningKnobs
import splice.dialect.responses.stream.ResponsesLooseFields
import splice.dialect.responses.tools.ResponsesToolPlan
import splice.upstream.ToolNameShortener

internal class ResponsesRequestBuilder(
    private val quirks: ResponsesQuirks,
    private val toolNames: ToolNameShortener = ToolNameShortener(),
) {

    // Collaborators that used to be file-level functions. Each is bound to THIS builder's quirks
    // where it needs them, so every relocated member kept its original argument list.
    private val toolPlan = ResponsesToolPlan(quirks, toolNames)
    private val knobs = ResponsesReasoningKnobs(quirks)
    private val looseFields = ResponsesLooseFields(quirks)
    private val ids = ResponsesStableIds()
    private val assembler = ResponsesRequestAssembler(quirks, toolNames)
    private val priorReasoning = ResponsesPriorReasoning()
    private val messages = ResponsesMessageFragments(quirks, toolNames)
    private val controls = ResponsesRequestControls(quirks)
    private val liteShape = ResponsesLiteShape(quirks)

    public fun build(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): BuiltRequest {
        // Standard Responses holds its initial direct-function surface in tools and appends later
        // declarations at their source boundaries. A source-deferred definition stays in the catalog
        // for a future tool_reference but must not be offered before that addition.
        val outputFormat = ResponsesOutputSchema.format(body, raw)
        val rawControls = controls.wire(raw)
        val wireBody = ResponsesOutputSchema.wireBody(body, outputFormat)
        val dynamicTools = ResponsesDynamicToolChanges.availability(wireBody)
        val initiallyAvailableBody = wireBody.copy(tools = dynamicTools.initiallyAvailable)
        // Responses-lite is the Codex synthetic outer executor's own tool shape. Its full catalog
        // remains provider-owned rather than being subjected to a direct-function availability rule.
        val lite = liteShape.isLite(opts)
        val surfaceBody = if (lite) wireBody else initiallyAvailableBody
        val partition = toolPlan.partition(surfaceBody, opts)
        val declareByName = toolPlan.declarationCandidates(surfaceBody, partition)
        val effort = knobs.resolveEffort(body, raw, opts)
        val summary = knobs.resolveSummary(raw, opts, effort)
        val walked = messages.walk(body, raw, opts, declareByName, effort)
        // replay_reasoning=false: the gateway cache must not put an earlier turn's reasoning back, and the current
        // turn keeps its own. A compaction is built exactly like a turn, so it follows the same rule.
        val input = if (opts.handoff.replay.v) {
            walked
        } else {
            JsonArray(priorReasoning.withoutPriorTurns(walked))
        }
        val instructions = body.system.orEmpty().ifEmpty { DEFAULT_INSTRUCTIONS }
        val reasoning = knobs.reasoningBlock(effort, summary, raw, opts)

        val conversationKey = ids.stablePromptCacheKey(body)
        val parts = RequestParts(
            input = input,
            instructions = instructions,
            reasoning = reasoning,
            tools = RequestToolSurface(
                partition = partition,
                currentAllowedTools = dynamicTools.currentNames.takeIf { !lite && dynamicTools.requiresRestriction },
            ),
            conversationKey = conversationKey,
            outputFormat = outputFormat,
            controls = rawControls,
        )
        val built = assembler.buildRequestObject(surfaceBody, opts, parts)
        return BuiltRequest(built.req, turnMeta(body, opts, parts, built, effort), built.toolSearch)
    }

    private fun turnMeta(
        body: AnthropicRequest,
        opts: BuildOptions,
        parts: RequestParts,
        built: BuiltBody,
        effort: String?,
    ): TurnMeta {
        // meta.summary reflects what was ACTUALLY sent (spark drops it → "none"), like Node's
        // `req.reasoning?.summary ?? 'none'` — not the computed-but-maybe-dropped value.
        val sentSummary = looseFields.sentSummary(parts.reasoning)
        return TurnMeta(
            compact = opts.compact,
            reasoning = TurnReasoning(
                showReasoning = opts.reasoning.display,
                effort = effort ?: "disabled",
                summary = sentSummary,
                budgetTokens = body.thinking?.budgetTokens,
            ),
            route = TurnRoute(
                stream = body.stream,
                originalModel = opts.models.original,
                upstreamModel = opts.models.upstream,
                clientMaxTokens = body.maxTokens?.takeIf { it > 0 },
            ),
            scope = TurnScope(
                // The reasoning cache's conversation scope — the SAME derivation the provider's
                // lookup closure uses, so capture (which only sees TurnMeta) and injection agree.
                conversationKey = parts.conversationKey,
                sessionId = opts.sessionId,
                reasoningHistory = opts.reasoningHistory.copy(),
                reasoningStorageKey = ids.reasoningStorageKey(body),
                // summaryParts is NOT passed: TurnScope's default constructs the turn's one instance.
                // Every continuation round reuses this meta object (continuationRequest bypasses
                // build()), so the dedup state it carries is genuinely turn-scoped.
            ),
            tools = toolSurface(built, body, parts.outputFormat),
        )
    }

    private fun toolSurface(built: BuiltBody, body: AnthropicRequest, format: JsonObject?): TurnToolSurface =
        TurnToolSurface(
            eager = built.toolsEager,
            deferred = built.toolsDeferred,
            outputToolName = format?.let { ResponsesOutputSchema.toolName(body) },
        )
}

private const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
