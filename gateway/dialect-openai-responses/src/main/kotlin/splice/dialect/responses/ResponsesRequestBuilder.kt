// PORT-OF: server/src/codex/translate-request.mjs + grok/translate-request.mjs @ 4ca99f7 —
// ONE Responses request builder parameterized by Quirks (the two Node files are ~90% identical;
// the v29 lesson: copies drift). Invariants:
//   - PURE: {req, meta} from (body, opts); never mutates the body (meta replaced v29's
//     body.__claudex* magic props);
//   - full fidelity on normal turns: never shrink input, never swap the model;
//   - COMPACT turns: tools stripped (a tooled compaction can answer with tool_use and empty
//     the text channel — v29 worst case), forced text-only instructions, model falls through
//     to body.model when compactModel is empty, and effort INHERITS THE SESSION (codex quirk;
//     a mismatch on model OR effort invalidates the whole prompt-cache prefix — the
//     "compaction ate my subscription" bug), unless the quirk pins one (grok: low);
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

package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import java.security.MessageDigest

/** The finite quirk surface separating codex / xai / openai-platform on this dialect. */
public data class ResponsesQuirks(
    val providerTag: String, // rides honest omission markers: "[image omitted by <tag> proxy: ...]"
    val store: Boolean = false,
    val cacheKeyStrategy: CacheKeyStrategy = CacheKeyStrategy.FIRST_MESSAGE_HASH,
    val effortLadder: EffortLadder = EffortLadder.CODEX,
    val supportsSummary: Boolean = true,
    val summaryRejectModelRegex: Regex? = Regex("spark", RegexOption.IGNORE_CASE),
    /** gpt-5.4-mini's ceiling is xhigh — the backend 400s effort=max on it (observed 2026-07-19). */
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
    /** codex-rs parity (read from source 2026-07-19): the gpt-5.6 family is served "responses-lite".
     *  Lite turns (non-compact): instructions ride as a developer input item (top-level field
     *  omitted), tools ride as an additional_tools input item (top-level field omitted),
     *  parallel_tool_calls is FORCED false (splice omitting it left the backend default parallel ON
     *  — a sequential-tool model spraying 30-50 parallel Task calls), reasoning.context=all_turns,
     *  and the x-openai-internal-codex-responses-lite header rides. Shape accepted by the live
     *  backend (direct probe 2026-07-19: 200, correct tool call). */
    val responsesLiteModelRegex: Regex? = Regex("gpt-5\\.6", RegexOption.IGNORE_CASE),
    val compactEffortPin: String? = null, // null = inherit session effort (the cache law)
    val emitToolChoice: Boolean = false,
    val emitStrict: Boolean = false,
    /** stream_options.reasoning_summary_delivery, sent only when a summary is requested. The
     *  ChatGPT backend serves ~2.3x more titled summary sections with "sequential_cutoff"
     *  (probed 2026-07-19: 30 parts/1546ch vs 14/646 on the same prompt) — the same value
     *  codex-rs sends. null = field omitted (grok/openai-platform). */
    val summaryDelivery: String? = null,
)

public enum class CacheKeyStrategy { FIRST_MESSAGE_HASH, SESSION_ID, OFF }

/**
 * Overlay the TOML `[providers.*.quirks]` primitives onto a provider's base profile so the parsed
 * table is REAL, not decorative (audit 2026-07-18: five of seven quirks were hard-coded and
 * ignored). Unset TOML fields keep the base value.
 */
// NB: TOML's effort_ceiling is deliberately NOT an overlay input — the effort LADDER (CODEX/GROK)
// already clamps the ceiling per provider; accepting a dead parameter here would just lie.
public fun ResponsesQuirks.withToml(
    store: Boolean? = null,
    cacheKey: String? = null,
    summaryField: Boolean? = null,
    compactEffort: String? = null,
    toolChoice: Boolean? = null,
): ResponsesQuirks = copy(
    store = store ?: this.store,
    cacheKeyStrategy = when (cacheKey) {
        "session-id" -> CacheKeyStrategy.SESSION_ID
        "off" -> CacheKeyStrategy.OFF
        "first-message-hash" -> CacheKeyStrategy.FIRST_MESSAGE_HASH
        else -> this.cacheKeyStrategy
    },
    supportsSummary = summaryField ?: this.supportsSummary,
    compactEffortPin = compactEffort ?: this.compactEffortPin,
    emitToolChoice = toolChoice ?: this.emitToolChoice,
)

public enum class EffortLadder { CODEX, GROK }

public data class BuiltRequest(val req: JsonObject, val meta: TurnMeta)

public data class BuildOptions(
    val compact: Boolean,
    val originalModel: String,
    val upstreamModel: String,
    val configEffort: String?,
    val configSummary: String?,
    val showReasoning: ReasoningDisplay,
    /**
     * Inject prior redacted_thinking envelopes into the request input (multi-turn continuity).
     * Independent of [includeEncryptedReasoning]. Keep OFF for deepest fresh reasoning.
     */
    val replayReasoning: InjectPriorReasoning,
    /**
     * Ask the server to return `reasoning.encrypted_content` on this turn's output.
     * Does NOT inject prior blobs into input. ON when reasoning is shown so we can store the
     * opaque handle for optional later replay (Grok Build / Codex always request this).
     */
    val includeEncryptedReasoning: RequestEncryptedReasoning = RequestEncryptedReasoning(true),
    val sessionId: String? = null,
    /** Decodes a redacted_thinking envelope back into a Responses reasoning input item. */
    val decodeReasoningEnvelope: (String) -> JsonObject?,
)

public class ResponsesRequestBuilder(private val quirks: ResponsesQuirks) {

    private val inputBuilder = ResponsesInputBuilder(quirks)

    public fun build(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): BuiltRequest {
        val input = buildJsonArray {
            for (msg in body.messages) {
                inputBuilder.appendMessage(this, msg, opts)
            }
        }
        val instructions = compactAwareInstructions(body.system, opts.compact)
        val effort = resolveEffort(body, raw, opts)
        val summary = resolveSummary(raw, opts, effort)
        val reasoning = reasoningBlock(effort, summary, opts)

        val req = buildRequestObject(body, opts, input, instructions, reasoning)
        // meta.summary reflects what was ACTUALLY sent (spark drops it → "none"), like Node's
        // `req.reasoning?.summary ?? 'none'` — not the computed-but-maybe-dropped value.
        val sentSummary = (reasoning?.get(FIELD_SUMMARY) as? JsonPrimitive)?.content ?: "none"

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
        )
        return BuiltRequest(req, meta)
    }

    private fun buildRequestObject(
        body: AnthropicRequest,
        opts: BuildOptions,
        input: JsonArray,
        instructions: String,
        reasoning: JsonObject?,
    ): JsonObject {
        // TIER-1 (#924): the request is a CLOSED DTO, not a hand-assembled JsonObject. A Chat-only
        // knob (stream_options.include_usage — the codex-breaking incident) cannot be added without a
        // field on ResponsesRequest, a reviewable type change. Byte-identical to the old put() set
        // (ResponsesRequestBuilderTest pins it): fields in declaration order, null optionals omitted.
        val tools = if (!opts.compact && body.tools.isNotEmpty()) toolsArray(body) else null
        val emitToolChoice = tools != null && quirks.emitToolChoice
        val include =
            if (!opts.compact && opts.includeEncryptedReasoning.v) listOf(ENCRYPTED_CONTENT_INCLUDE) else null
        val shape = wireShape(quirks.isLite(opts), input, instructions, tools)
        val dto = ResponsesRequest(
            model = opts.upstreamModel,
            input = shape.input,
            store = quirks.store,
            stream = true,
            include = include,
            promptCacheKey = cacheKey(body, opts),
            instructions = shape.instructions,
            tools = shape.tools,
            toolChoice = if (emitToolChoice) toolChoice(body) else null,
            parallelToolCalls = parallelToolCallsFor(emitToolChoice, body, opts),
            reasoning = reasoning,
            streamOptions = summaryDeliveryOptions(reasoning),
        )
        return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), dto) as JsonObject
    }

    // ── tools ────────────────────────────────────────────────────────────────

    private fun toolsArray(body: AnthropicRequest) = buildJsonArray {
        for (t in body.tools) add(toolObject(t))
    }

    private fun toolObject(t: ToolDefinition): JsonObject = buildJsonObject {
        put("type", FIELD_FUNCTION)
        put("name", t.name)
        put("description", t.description ?: "")
        // Both Node references send `properties:{}` on a bare object schema; some strict
        // validators reject an object schema without it (audit 2026-07-18).
        put(
            "parameters",
            t.inputSchema ?: buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            },
        )
        if (quirks.emitStrict && t.strict == true) put("strict", true)
    }

    private fun toolChoice(body: AnthropicRequest): JsonElement {
        val choice = body.toolChoice
        return when {
            choice == null || choice.type == "auto" -> JsonPrimitive("auto")
            choice.type == "none" -> JsonPrimitive("none")
            choice.type == "any" -> JsonPrimitive("required")
            choice.type == "tool" && choice.name != null -> buildJsonObject {
                put("type", FIELD_FUNCTION)
                put(FIELD_FUNCTION, buildJsonObject { put("name", choice.name) })
            }
            else -> JsonPrimitive("auto")
        }
    }

    /** codex-rs parity: 5.6-family models get parallel_tool_calls=false whenever tools ride —
     *  their own CLI forces it (responses-lite). Wins over the grok-style toolChoice negotiation;
     *  null = omit the field (backend default). */
    private fun parallelToolCallsFor(
        emitToolChoice: Boolean,
        body: AnthropicRequest,
        opts: BuildOptions,
    ): Boolean? = when {
        // Unconditional on lite turns — codex-rs sends the field always, and the backend 400s a
        // lite-header request without an explicit false (live error 2026-07-19, toolless turn).
        quirks.isLite(opts) -> false
        emitToolChoice -> body.toolChoice?.disableParallelToolUse != true
        else -> null
    }

    // ── knobs ────────────────────────────────────────────────────────────────

    private fun compactAwareInstructions(system: String?, compact: Boolean): String {
        val base = system.orEmpty()
        if (!compact) return base.ifEmpty { "You are a helpful assistant." }
        return listOf(
            base,
            "",
            "COMPACT MODE (critical): You are summarizing a coding session for another agent.",
            "Respond with ONLY a detailed plain-text summary. No tools. No function calls.",
            "Do not put the summary only in reasoning — the final message text MUST contain the full summary.",
            "Structure with headings: Goal, Decisions, Files touched, Current state, Errors, Next steps, Constraints.",
            "Be concrete (paths, commands, numbers). Omit boilerplate.",
        ).filter { it.isNotEmpty() }.joinToString("\n") // Node .filter(Boolean): drops the "" separator
    }

    private fun cacheKey(body: AnthropicRequest, opts: BuildOptions): String? = when (quirks.cacheKeyStrategy) {
        CacheKeyStrategy.OFF -> null
        CacheKeyStrategy.SESSION_ID -> opts.sessionId?.let { "claude-grok:$it" }
        CacheKeyStrategy.FIRST_MESSAGE_HASH -> stablePromptCacheKey(body)
    }

    private fun resolveEffort(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): String? {
        // PORT: grok pins compaction effort to `low` (Node grok/translate-request.mjs:141
        // `req.reasoning = { effort: 'low' }`) — bypasses budget/config so a compaction started
        // at high effort can't run the summarizer at full cost. codex leaves the pin null → inherits.
        if (opts.compact) quirks.compactEffortPin?.let { return it }
        if (body.thinking?.disabled == true && quirks.effortLadder == EffortLadder.CODEX) return null
        var effort = looseEffort(raw)
        if (effort == null) {
            // v27: the /effort picker (budget) WINS over the config/env fallback
            val budgetEffort = body.thinking
                ?.takeIf { !it.disabled }
                ?.budgetTokens
                ?.let { effortFromBudget(it, quirks.effortLadder) }
            effort = budgetEffort ?: normalizeEffort(opts.configEffort, quirks.effortLadder) ?: "high"
        }
        effort = flooredForVisibility(effort, opts.showReasoning)
        effort = flooredForGrok(effort, quirks.effortLadder)
        return clampedForModelCeiling(effort, opts.upstreamModel, quirks.effortMaxRejectModelRegex)
    }

    // NB: `as? JsonObject` NOT `?.jsonObject` — the latter THROWS on a non-object (e.g. a client
    // sending `"reasoning":"high"` as a bare string); Node's optional chaining degrades to default.
    private fun looseEffort(raw: JsonObject): String? = sequenceOf(
        raw[FIELD_EFFORT],
        raw["reasoning_effort"],
        (raw["output_config"] as? JsonObject)?.get(FIELD_EFFORT),
        (raw["metadata"] as? JsonObject)?.get(FIELD_EFFORT),
        (raw[FIELD_REASONING] as? JsonObject)?.get(FIELD_EFFORT),
    ).mapNotNull { (it as? JsonPrimitive)?.content }
        .mapNotNull { normalizeEffort(it, quirks.effortLadder) }
        .firstOrNull()

    private fun resolveSummary(raw: JsonObject, opts: BuildOptions, effort: String?): String? {
        if (!quirks.supportsSummary || effort == null) return null
        // Operator-controlled via TOML/env/state (Knob.SUMMARY default = "detailed").
        // Precedence: request body fields > configSummary > default detailed.
        // showReasoning=off still suppresses the field (summary "none").
        if (opts.showReasoning.isOff) return "none"
        val requested = sequenceOf(
            (raw[FIELD_REASONING] as? JsonObject)?.get(FIELD_SUMMARY),
            raw["reasoning_summary"],
            (raw["output_config"] as? JsonObject)?.get("reasoning_summary"),
        ).mapNotNull { (it as? JsonPrimitive)?.content }
            .mapNotNull { normalizeSummary(it) }
            .firstOrNull()
        // v27 visibility fold (the header's "folds summary to detailed" clause — was unimplemented,
        // audit 2026-07-18): when reasoning is VISIBLE, a REQUEST-level weak summary (none/auto/
        // concise from the model/Claude Code) is floored to detailed so `summary_text` actually
        // fills. The OPERATOR's configSummary stays authoritative (a deliberate `concise` in
        // TOML/env is respected) — the fold defends against the request, not the operator.
        val folded = requested?.let { if (it in SUMMARY_FLOOR_TO_DETAILED) SUMMARY_DETAILED else it }
        return folded ?: normalizeSummary(opts.configSummary) ?: SUMMARY_DETAILED
    }

    /** codex-rs parity: delivery rides ONLY alongside an actual summary request. */
    private fun summaryDeliveryOptions(reasoning: JsonObject?): JsonObject? {
        val delivery = quirks.summaryDelivery ?: return null
        if (reasoning?.get("summary") == null) return null
        return buildJsonObject { put("reasoning_summary_delivery", delivery) }
    }

    private fun reasoningBlock(effort: String?, summary: String?, opts: BuildOptions): JsonObject? {
        if (effort == null) return null
        val dropSummary = quirks.summaryRejectModelRegex?.containsMatchIn(opts.upstreamModel) == true ||
            summary == null || summary == "none"
        return buildJsonObject {
            put(FIELD_EFFORT, effort)
            if (!dropSummary) put(FIELD_SUMMARY, summary)
            // codex-rs lite parity: reasoning context spans the session, not just the current turn.
            if (quirks.isLite(opts)) put("context", "all_turns")
        }
    }
}

private val GROK_EFFORTS = setOf("low", EFFORT_MEDIUM, "high")

/**
 * Visibility floor: never RAISES a deliberate low/medium/high pick, only floors none/minimal to
 * low so a hidden reasoning knob still surfaces something when showReasoning != off.
 */
private fun flooredForVisibility(effort: String?, showReasoning: ReasoningDisplay): String? {
    if (showReasoning.isOff) return effort
    val hidden = effort == EFFORT_MINIMAL || effort == "none"
    return if (hidden) "low" else effort
}

/** grok reasoning cannot be disabled — floor anything off the grok ladder to low. */
private fun flooredForGrok(effort: String?, ladder: EffortLadder): String? {
    if (ladder != EffortLadder.GROK) return effort
    return effort?.takeIf { it in GROK_EFFORTS } ?: "low"
}

/** Per-model effort ceiling: models matching the quirk regex reject effort=max — clamp to xhigh. */
private fun clampedForModelCeiling(effort: String?, upstreamModel: String, rejectMax: Regex?): String? {
    if (effort != "max" || rejectMax?.containsMatchIn(upstreamModel) != true) return effort
    return EFFORT_XHIGH
}

/**
 * Anthropic content blocks → Responses input items. One private helper per input-item family —
 * the ported shape of translate-request.mjs's message/block walk, kept flat so no single handler
 * nests the whole cascade.
 */
private class ResponsesInputBuilder(private val quirks: ResponsesQuirks) {

    fun appendMessage(
        sink: JsonArrayBuilder,
        msg: AnthropicMessage,
        opts: BuildOptions,
    ) {
        for (block in msg.content) {
            appendBlock(sink, msg.role, block, opts)
        }
    }

    private fun appendBlock(
        sink: JsonArrayBuilder,
        role: String,
        block: ContentBlock,
        opts: BuildOptions,
    ) {
        when (block) {
            is TextBlock -> sink.add(roleText(role, block.text))
            is ImageBlock -> appendImage(sink, block, opts)
            is DocumentBlock -> appendDocument(sink, block, opts)
            is RedactedThinkingBlock -> appendRedactedThinking(sink, block, opts)
            is ToolUseBlock -> appendToolUse(sink, block, opts)
            is ToolResultBlock -> appendToolResult(sink, block, opts)
            is ThinkingBlock -> Unit // visible thinking never rides back upstream
            is UnknownBlock -> Unit // unknown client blocks are dropped, never crash
        }
    }

    private fun appendDocument(
        sink: JsonArrayBuilder,
        block: DocumentBlock,
        opts: BuildOptions,
    ) {
        if (opts.compact) return
        sink.add(
            roleText(
                "user",
                "[document omitted by ${quirks.providerTag} proxy: " +
                    "${block.source?.mediaType ?: "unknown type"}]",
            ),
        )
    }

    private fun appendRedactedThinking(
        sink: JsonArrayBuilder,
        block: RedactedThinkingBlock,
        opts: BuildOptions,
    ) {
        if (!opts.compact && opts.replayReasoning.v) {
            opts.decodeReasoningEnvelope(block.data)?.let { sink.add(it) }
        }
    }

    private fun appendToolUse(
        sink: JsonArrayBuilder,
        block: ToolUseBlock,
        opts: BuildOptions,
    ) {
        if (opts.compact) return
        sink.add(
            buildJsonObject {
                put("type", "function_call")
                put("call_id", block.id)
                put("name", block.name)
                put("arguments", block.input.toString())
            },
        )
    }

    private fun appendImage(sink: JsonArrayBuilder, block: ImageBlock, opts: BuildOptions) {
        if (opts.compact) return // compact is a text-only summarizer
        val part = imagePart(block.source)
        if (part != null) {
            sink.add(
                buildJsonObject {
                    put("role", "user")
                    put(FIELD_CONTENT, buildJsonArray { add(part) })
                },
            )
        } else {
            sink.add(roleText("user", "[image omitted by ${quirks.providerTag} proxy: unsupported source]"))
        }
    }

    private fun appendToolResult(
        sink: JsonArrayBuilder,
        block: ToolResultBlock,
        opts: BuildOptions,
    ) {
        val text = block.content.filterIsInstance<TextBlock>().joinToString("") { it.text }
        if (opts.compact) {
            // fold tool results into plain user text so the summarizer still sees them
            if (text.isNotEmpty()) sink.add(roleText("user", "[tool_result ${block.toolUseId}] $text"))
            return
        }
        sink.add(
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", block.toolUseId)
                put("output", text)
            },
        )
        // v25: images inside tool_result (Read on a PNG, screenshots) used to vanish —
        // function_call_output.output is string-only, so ride them in a follow-up user message.
        val imageParts = block.content.filterIsInstance<ImageBlock>().mapNotNull { imagePart(it.source) }
        if (imageParts.isNotEmpty()) {
            sink.add(toolResultImageMessage(block.toolUseId, imageParts))
        }
    }

    private fun toolResultImageMessage(toolUseId: String, imageParts: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("role", "user")
            put(
                FIELD_CONTENT,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", "[images from tool_result $toolUseId]")
                        },
                    )
                    imageParts.forEach { add(it) }
                },
            )
        }

    private fun imagePart(source: MediaSource?): JsonObject? = when {
        source == null -> null
        source.type == "base64" && !source.data.isNullOrEmpty() -> {
            // Build the data URL once into a capacity-sized buffer — the base64 payload is often
            // multi-MB for screenshots; avoid intermediate template concat copies.
            val mime = source.mediaType?.takeIf { it.isNotEmpty() } ?: "image/png"
            val data = source.data!!
            val url = StringBuilder(DATA_URL_PREFIX.length + mime.length + BASE64_SEPARATOR.length + data.length)
                .append(DATA_URL_PREFIX).append(mime).append(BASE64_SEPARATOR).append(data)
                .toString()
            buildJsonObject {
                put("type", "input_image")
                put("image_url", url)
            }
        }
        source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
            put("type", "input_image")
            put("image_url", source.url)
        }
        else -> null
    }

    private fun roleText(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put(FIELD_CONTENT, text)
    }
}

// Wire field names that repeat across the request/knob builders.
private const val FIELD_CONTENT = "content"
private const val FIELD_EFFORT = "effort"
private const val FIELD_SUMMARY = "summary"
private const val FIELD_REASONING = "reasoning"
private const val FIELD_FUNCTION = "function"

// Data-URL pieces for base64 image parts (capacity math uses their lengths — no magic numbers).
private const val DATA_URL_PREFIX = "data:"
private const val BASE64_SEPARATOR = ";base64,"

// Effort/summary tokens shared by the alias tables and the resolvers.
private const val EFFORT_MEDIUM = "medium"
private const val EFFORT_XHIGH = "xhigh"
private const val EFFORT_MINIMAL = "minimal"
private const val SUMMARY_DETAILED = "detailed"
private const val SUMMARY_CONCISE = "concise"

/** Compact-mode model+effort pinning helper: empty compactModel = session model (the cache law). */
public fun compactUpstreamModel(compact: Boolean, compactModel: String?, sessionModel: String): String =
    if (compact && !compactModel.isNullOrEmpty()) compactModel else sessionModel

/** Codex-parity cache key: sha256 of the FIRST user message's text, stable per conversation. */
public fun stablePromptCacheKey(body: AnthropicRequest): String? {
    val first = body.messages.firstOrNull { it.role == "user" } ?: return null
    val seed = first.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    if (seed.isEmpty()) return null
    val md = SHA256.get()
    md.reset()
    val digest = md.digest(seed.toByteArray(Charsets.UTF_8))
    // Only the first HASH_PREFIX_LEN/2 bytes → HASH_PREFIX_LEN hex chars ("splice-" + 32).
    val hexChars = CharArray(HASH_PREFIX_LEN)
    var hi = 0
    for (i in 0 until HASH_PREFIX_BYTES) {
        val b = digest[i].toInt() and BYTE_MASK
        hexChars[hi++] = HEX_DIGITS[b ushr NIBBLE_BITS]
        hexChars[hi++] = HEX_DIGITS[b and NIBBLE_MASK]
    }
    return "splice-" + String(hexChars)
}

private const val HASH_PREFIX_LEN = 32
private const val HASH_PREFIX_BYTES = HASH_PREFIX_LEN / 2
private const val BYTE_MASK = 0xff
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0f
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

// MessageDigest is not thread-safe; a ThreadLocal avoids the provider lookup per turn without sharing.
private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

// the alias table IS the contract
public fun normalizeEffort(raw: String?, ladder: EffortLadder): String? {
    val s = raw?.trim()?.lowercase().orEmpty()
    if (s.isEmpty()) return null
    return when (ladder) {
        EffortLadder.CODEX -> normalizeCodexEffort(s)
        EffortLadder.GROK -> normalizeGrokEffort(s)
    }
}

private fun normalizeCodexEffort(s: String): String? = when (s) {
    "ultracode", "ultra" -> "max"
    "extra_high", "extra-high", "extrahigh" -> EFFORT_XHIGH
    "standard", "normal" -> EFFORT_MEDIUM
    "light", "fast" -> "low"
    "heavy", "extended" -> "high"
    in CODEX_EFFORTS -> s
    else -> null
}

private fun normalizeGrokEffort(s: String): String? = when (s) {
    "high", EFFORT_XHIGH, "max", "ultra", "ultracode", "extra_high", "extra-high",
    "extrahigh", "heavy", "extended",
    -> "high"
    EFFORT_MEDIUM, "standard", "normal" -> EFFORT_MEDIUM
    "low", EFFORT_MINIMAL, "none", "off", "fast", "light" -> "low"
    else -> null
}

private val CODEX_EFFORTS = setOf("none", EFFORT_MINIMAL, "low", EFFORT_MEDIUM, "high", EFFORT_XHIGH, "max")

// Hoisted so resolvers never allocate a fresh set per call on the request-build path.
private val SUMMARY_CANONICAL = setOf("auto", SUMMARY_CONCISE, SUMMARY_DETAILED, "none")

// v27 visibility fold: these weak/absent summaries floor to detailed when reasoning is shown.
private val SUMMARY_FLOOR_TO_DETAILED = setOf("none", "auto", SUMMARY_CONCISE)
private val SUMMARY_AS_DETAILED = setOf("full", "verbose", "long")
private val SUMMARY_AS_CONCISE = setOf("short", "brief")
private val SUMMARY_AS_NONE = setOf("off", "false", "0")

public fun normalizeSummary(raw: String?): String? {
    val s = raw?.trim()?.lowercase().orEmpty()
    return when {
        s.isEmpty() -> null
        s in SUMMARY_CANONICAL -> s
        s in SUMMARY_AS_DETAILED -> SUMMARY_DETAILED
        s in SUMMARY_AS_CONCISE -> SUMMARY_CONCISE
        s in SUMMARY_AS_NONE -> "none"
        else -> null
    }
}

// tier table
public fun effortFromBudget(budget: Long, ladder: EffortLadder): String? = when (ladder) {
    EffortLadder.CODEX -> codexBudgetEffort(budget)
    EffortLadder.GROK -> when {
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> EFFORT_MEDIUM
        else -> "low"
    }
}

private fun codexBudgetEffort(budget: Long): String = when {
    budget >= BUDGET_MAX -> "max"
    budget >= BUDGET_XHIGH -> EFFORT_XHIGH
    budget >= BUDGET_HIGH -> "high"
    budget >= BUDGET_MEDIUM -> EFFORT_MEDIUM
    else -> "low"
}

private const val BUDGET_MAX = 64_000L
private const val BUDGET_XHIGH = 32_000L
private const val BUDGET_HIGH = 10_000L
private const val BUDGET_MEDIUM = 2_000L
