// NEW: DTO assembly — owns the closed ResponsesRequest DTO, holds liteShape/toolWire/hints/ids, and
// the two "knobs" it keeps (toolChoiceFor, parallelToolCallsFor) that exist solely to fill two of
// that DTO's fields. Split out of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign).
// BuiltBody and RequestParts come with it because their own KDoc says they exist only to keep
// buildRequestObject under LongParameterList's 6-arg threshold; they follow their sole owner. Every
// relocated member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolChoiceMapping
import splice.spi.ToolSearchController

/** [ResponsesRequestAssembler.buildRequestObject]'s internal return — the request bytes plus the
 *  tool-surface facts only the builder knows (the partition sizes for TurnMeta, the search
 *  controller for BuiltRequest). Keeping these OFF buildRequestObject's parameter list is why it
 *  stays under LongParameterList's function threshold instead of growing a 6th argument. */
internal data class BuiltBody(
    val req: JsonObject,
    val toolSearch: ToolSearchController?,
    val toolsEager: Int?,
    val toolsDeferred: Int?,
)

/** The pieces [ResponsesRequestBuilder.build] computes before the DTO is assembled, bundled as one
 *  argument — the INPUT-side sibling of [BuiltBody], and for the same reason: `partition` was the
 *  6th parameter that tripped LongParameterList (review 2026-07-25). Bundling, not dropping: every
 *  field is still read verbatim by buildRequestObject. */
internal data class RequestParts(
    val input: JsonArray,
    val instructions: String,
    val reasoning: JsonObject?,
    val partition: ToolPartition?,
)

internal class ResponsesRequestAssembler(private val quirks: ResponsesQuirks) {

    private val toolWire = ToolWireObjects()
    private val liteShape = ResponsesLiteShape(quirks)
    private val hints = ResponsesClientHints()
    private val ids = ResponsesStableIds()
    private val toolPlan = ResponsesToolPlan(quirks)
    private val knobs = ResponsesReasoningKnobs(quirks)

    internal fun buildRequestObject(body: AnthropicRequest, opts: BuildOptions, parts: RequestParts): BuiltBody {
        // TIER-1 (#924): the request is a CLOSED DTO, not a hand-assembled JsonObject. A Chat-only
        // knob (stream_options.include_usage — the codex-breaking incident) cannot be added without a
        // field on ResponsesRequest, a reviewable type change. Byte-identical to the old put() set
        // (ResponsesRequestBuilderTest pins it): fields in declaration order, null optionals omitted.
        val searchLimit = quirks.toolSurface?.searchLimit ?: DEFAULT_SEARCH_LIMIT
        val tools = parts.partition?.let {
            toolWire.toolsSection(it, quirks.emitStrict, quirks.forceStrictFalse, searchLimit)
        }
        val lite = liteShape.isLite(opts)
        // Lite turns carry tools as an additional_tools input item, not top-level `tools`; without
        // an explicit tool_choice the backend never enables function-calling from them (the model
        // then improvises tool calls in text — stuck/looping turns). codex-rs sends tool_choice:"auto"
        // unconditionally (core/src/client.rs:896), so lite MUST too, independent of the grok-style
        // emitToolChoice negotiation that codex otherwise leaves off.
        val emitToolChoice = tools != null && (quirks.emitToolChoice || lite)
        val include =
            if (!opts.compact && opts.includeEncryptedReasoning.v) listOf(ENCRYPTED_CONTENT_INCLUDE) else null
        val shape = liteShape.wireShape(lite, parts.input, parts.instructions, tools)
        val dto = ResponsesRequest(
            model = opts.upstreamModel,
            input = shape.input,
            store = quirks.store,
            stream = true,
            include = include,
            promptCacheKey = cacheKey(body, opts),
            instructions = shape.instructions,
            tools = shape.tools,
            toolChoice = toolChoiceFor(emitToolChoice, lite, body),
            parallelToolCalls = parallelToolCallsFor(emitToolChoice, body, opts),
            reasoning = parts.reasoning,
            text = hints.liteTextBlock(quirks, lite),
            clientMetadata = hints.clientMetadataBlock(quirks, lite, opts, cacheKey(body, opts)),
            streamOptions = knobs.summaryDeliveryOptions(parts.reasoning),
        )
        val req = responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), dto) as JsonObject
        // Stamped only when a policy is actually CONFIGURED for this provider (quirks.toolSurface
        // != null) — never for every provider globally just because tools rode this turn. A
        // configured-but-not-triggering turn (wrong model, latch closed, below the floor) still
        // stamps 0, which is the real "why no deferral happened" signal the perf JSONL needs.
        val surfaceInPlay = parts.partition?.takeIf { quirks.toolSurface != null }
        return BuiltBody(
            req = req,
            toolSearch = toolPlan.toolSearchControllerFor(parts.partition, opts),
            toolsEager = surfaceInPlay?.eager?.size,
            toolsDeferred = surfaceInPlay?.deferred?.size,
        )
    }

    /** Lite pins "auto" (the only value codex-rs validated against additional_tools — client.rs:896);
     *  non-lite keeps the negotiated choice for grok's specific-tool path; null omits the field. */
    internal fun toolChoiceFor(emitToolChoice: Boolean, lite: Boolean, body: AnthropicRequest): JsonElement? = when {
        !emitToolChoice -> null
        lite -> JsonPrimitive("auto")
        else -> ToolChoiceMapping.openAiToolChoice(body.toolChoice)
    }

    /** codex-rs parity: 5.6-family models get parallel_tool_calls=false whenever tools ride —
     *  their own CLI forces it (responses-lite). Wins over the grok-style toolChoice negotiation;
     *  null = omit the field (backend default). */
    internal fun parallelToolCallsFor(
        emitToolChoice: Boolean,
        body: AnthropicRequest,
        opts: BuildOptions,
    ): Boolean? = when {
        // Always PRESENT on lite turns — codex-rs sends the field always, and the backend 400s a
        // lite-header request without an explicit value (live error 2026-07-19, toolless turn).
        // The VALUE is a quirk (2026-07-31): codex-rs does not hardcode it either, it sends
        // `model_info.supports_parallel_tool_calls`, a per-model configurable. Default stays false
        // — omitting the field once left the backend default parallel ON and gpt-5.6 sprayed 30-50
        // parallel Task calls (see the header). Note that pathology came from OMITTING the field,
        // which is not the same as sending an explicit true; the true case is untested, which is
        // exactly why this is an opt-in knob and not a changed default.
        // Even with the knob on (review of #71 round 2): a client's explicit
        // disable_parallel_tool_use=true wins — the gateway must not override a request the
        // client asked to serialize — and a TOOLLESS turn stays false (nothing to parallelize,
        // and explicit-true-without-tools is an untested combination upstream).
        liteShape.isLite(opts) ->
            quirks.liteParallelToolCalls &&
                body.tools.isNotEmpty() &&
                body.toolChoice?.disableParallelToolUse != true
        emitToolChoice -> body.toolChoice?.disableParallelToolUse != true
        else -> null
    }

    internal fun cacheKey(body: AnthropicRequest, opts: BuildOptions): String? = when (quirks.cacheKeyStrategy) {
        CacheKeyStrategy.OFF -> null
        // Prefix from quirks.providerTag (not a hard-coded "claude-grok:") so TOML cache_key=session-id
        // on any Responses provider stays in its own cache namespace.
        CacheKeyStrategy.SESSION_ID -> opts.sessionId?.let { "${quirks.providerTag}:$it" }
        CacheKeyStrategy.FIRST_MESSAGE_HASH -> ids.stablePromptCacheKey(body)
    }
}
