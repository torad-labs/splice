// NEW: DTO assembly — owns the closed ResponsesRequest DTO, holds liteShape/toolWire/hints/ids, and
// the two "knobs" it keeps (toolChoiceFor, parallelToolCallsFor) that exist solely to fill two of
// that DTO's fields. Split out of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign).
// BuiltBody and RequestParts come with it because their own KDoc says they exist only to keep
// buildRequestObject under LongParameterList's 6-arg threshold; they follow their sole owner. Every
// relocated member kept its identical name and argument list.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.ResponsesClientHints
import splice.dialect.responses.ResponsesLiteShape
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ResponsesReasoningKnobs
import splice.dialect.responses.tools.ResponsesToolPlan
import splice.dialect.responses.tools.ToolPartition
import splice.dialect.responses.tools.ToolWireObjects
import splice.upstream.ToolNameShortener
import splice.upstream.ToolSearchPolicy

/** [ResponsesRequestAssembler.buildRequestObject]'s internal return — the request bytes plus the
 *  tool-surface facts only the builder knows (the partition sizes for TurnMeta, the search
 *  controller for BuiltRequest). Keeping these OFF buildRequestObject's parameter list is why it
 *  stays under LongParameterList's function threshold instead of growing a 6th argument. */
internal data class BuiltBody(
    val req: JsonObject,
    val toolSearch: ToolSearchPolicy?,
    val toolsEager: Int?,
    val toolsDeferred: Int?,
)

/** The declared tool surface and its current call restrictions belong to the same source timeline. */
internal data class RequestToolSurface(
    val partition: ToolPartition?,
    /** Null preserves ordinary choice handling. A value constrains the public direct-function surface. */
    val currentAllowedTools: List<String>? = null,
)

/** Input, output settings, and tool availability computed before the closed request DTO is assembled. */
internal data class RequestParts(
    val input: JsonArray,
    val instructions: String,
    val reasoning: JsonObject?,
    val tools: RequestToolSurface,
    val conversationKey: String?,
    val outputFormat: JsonObject? = null,
    val controls: ResponsesWireRequestControls = ResponsesWireRequestControls(),
)

internal class ResponsesRequestAssembler(
    private val quirks: ResponsesQuirks,
    private val toolNames: ToolNameShortener = ToolNameShortener(),
) {

    private val toolWire = ToolWireObjects(toolNames)
    private val liteShape = ResponsesLiteShape(quirks)
    private val hints = ResponsesClientHints()
    private val toolPlan = ResponsesToolPlan(quirks)
    private val knobs = ResponsesReasoningKnobs(quirks)

    internal fun buildRequestObject(body: AnthropicRequest, opts: BuildOptions, parts: RequestParts): BuiltBody {
        // TIER-1 (#924): the request is a CLOSED DTO, not a hand-assembled JsonObject. A Chat-only
        // knob (stream_options.include_usage — the codex-breaking incident) cannot be added without a
        // field on ResponsesRequest, a reviewable type change. Byte-identical to the old put() set
        // (ResponsesRequestBuilderTest pins it): fields in declaration order, null optionals omitted.
        val tools = parts.tools.partition?.let { partition ->
            toolWire.toolsSection(
                partition,
                quirks.tools.emitStrict,
                quirks.tools.forceStrictFalse,
                quirks.tools.normalizeToolSchemas,
                quirks.tools.toolSurface,
            )
        }
        val lite = liteShape.isLite(opts)
        // Lite turns carry tools as an additional_tools input item, not top-level `tools`; without
        // an explicit tool_choice the backend never enables function-calling from them (the model
        // then improvises tool calls in text — stuck/looping turns). codex-rs sends tool_choice:"auto"
        // for implicit choice (core/src/client.rs:896). Explicit caller choices are preserved below;
        // a provider that cannot honor them refuses before assembly.
        val restrictedTools = parts.tools.currentAllowedTools
        val emitToolChoice = restrictedTools != null || body.toolChoice != null ||
            tools != null && (quirks.tools.emitToolChoice || lite)
        val include = if (opts.handoff.includeEncrypted.v) listOf(ENCRYPTED_CONTENT_INCLUDE) else null
        val shape = liteShape.wireShape(lite, parts.input, parts.instructions, tools)
        val cacheKey = cacheKey(opts, parts.conversationKey)
        val dto = ResponsesRequest(
            model = opts.models.upstream,
            input = shape.input,
            store = quirks.backend.store,
            stream = true,
            include = include,
            promptCacheKey = cacheKey,
            promptCacheRetention = quirks.backend.promptCache.retention,
            instructions = shape.instructions,
            tools = shape.tools,
            toolChoice = toolChoiceFor(emitToolChoice, lite, body, restrictedTools),
            parallelToolCalls = parallelToolCallsFor(emitToolChoice, body, opts),
            reasoning = parts.reasoning,
            text = parts.outputFormat?.let { format ->
                JsonObject(hints.liteTextBlock(quirks, lite).orEmpty() + ("format" to format))
            } ?: hints.liteTextBlock(quirks, lite),
            clientMetadata = hints.clientMetadataBlock(quirks, lite, opts, cacheKey),
            streamOptions = knobs.summaryDeliveryOptions(parts.reasoning),
            maxOutputTokens = body.maxTokens.takeIf { quirks.request.emitMaxOutputTokens },
            temperature = parts.controls.temperature,
            topP = parts.controls.topP,
            serviceTier = parts.controls.serviceTier,
        )
        val req = responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), dto) as JsonObject
        // Stamped only when a policy is actually CONFIGURED for this provider (quirks.tools.toolSurface
        // != null) — never for every provider globally just because tools rode this turn. A
        // configured-but-not-triggering turn (wrong model, latch closed, below the floor) still
        // stamps 0, which is the real "why no deferral happened" signal the perf JSONL needs.
        val surfaceInPlay = parts.tools.partition?.takeIf { quirks.tools.toolSurface != null }
        return BuiltBody(
            req = req,
            toolSearch = toolPlan.toolSearchControllerFor(parts.tools.partition, opts),
            toolsEager = surfaceInPlay?.eager?.size,
            toolsDeferred = surfaceInPlay?.deferred?.size,
        )
    }

    /** Explicit caller choices win on every turn shape. Responses named choices are flat, not Chat's function object. */
    internal fun toolChoiceFor(
        emitToolChoice: Boolean,
        lite: Boolean,
        body: AnthropicRequest,
        currentAllowedTools: List<String>? = null,
    ): JsonElement? {
        currentAllowedTools?.let { allowed -> return restrictedChoice(body, allowed) }
        val choice = body.toolChoice ?: return implicitChoice(emitToolChoice, lite, body)
        return when (choice.type) {
            "none" -> JsonPrimitive("none")
            "any", REQUIRED_TOOL_CHOICE -> JsonPrimitive(REQUIRED_TOOL_CHOICE)
            "tool" -> choice.name?.let(::functionChoice)
            "auto" -> JsonPrimitive("auto")
            else -> null // Unknown choices are refused before building, never rewritten to auto.
        }
    }

    /** allowed_tools limits the public direct surface without changing the stable top-level declarations. */
    private fun restrictedChoice(
        body: AnthropicRequest,
        allowed: List<String>,
    ): JsonElement? = when (body.toolChoice?.type) {
        null, "auto" -> allowedToolsChoice("auto", allowed)
        "any", REQUIRED_TOOL_CHOICE -> allowedToolsChoice(REQUIRED_TOOL_CHOICE, allowed)
        "none" -> JsonPrimitive("none")
        "tool" -> body.toolChoice?.name?.takeIf { it in allowed }?.let(::functionChoice) ?: JsonPrimitive("none")
        else -> null
    }

    private fun allowedToolsChoice(mode: String, allowed: List<String>): JsonElement =
        if (allowed.isEmpty()) {
            JsonPrimitive("none")
        } else {
            buildJsonObject {
                put("type", "allowed_tools")
                put("mode", mode)
                put(
                    "tools",
                    buildJsonArray {
                        allowed.forEach { name -> add(functionChoice(name)) }
                    },
                )
            }
        }

    private fun functionChoice(name: String): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", toolNames.shorten(name))
    }

    private fun implicitChoice(emit: Boolean, lite: Boolean, body: AnthropicRequest): JsonElement? {
        if (emit) return JsonPrimitive("auto")
        return if (lite && body.tools.isNotEmpty()) JsonPrimitive("auto") else null
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
            quirks.lite.liteParallelToolCalls &&
                body.tools.isNotEmpty() &&
                body.toolChoice?.disableParallelToolUse != true
        emitToolChoice -> body.toolChoice?.disableParallelToolUse != true
        else -> null
    }

    internal fun cacheKey(opts: BuildOptions, conversationKey: String?): String? =
        when (quirks.backend.promptCache.key) {
            CacheKeyStrategy.OFF -> null
            // Prefix from quirks.providerTag (not a hard-coded "claude-grok:") so TOML cache_key=session-id
            // on any Responses provider stays in its own cache namespace.
            CacheKeyStrategy.SESSION_ID -> opts.sessionId?.let { "${quirks.providerTag}:$it" }
            CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH ->
                opts.sessionId?.let { "${quirks.providerTag}:$it" } ?: conversationKey
            CacheKeyStrategy.FIRST_MESSAGE_HASH -> conversationKey
        }
}

private const val REQUIRED_TOOL_CHOICE = "required"
