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
//   - replay (gated, never on compact): redacted_thinking decodes back into a reasoning input
//     item IN POSITION; include=['reasoning.encrypted_content'] requested;
//   - cache key: first-message-hash 'splice-<sha256(first user text)[:32]>' (codex — stable
//     across per-turn system-reminder drift) or session-id 'claude-grok:<sid>' (grok);
//   - effort precedence (v27): explicit body fields > /effort picker (thinking.budget_tokens)
//     > config/env fallback > high; visibility floor when showReasoning != off never RAISES
//     a deliberate pick, only floors none/minimal -> low and folds summary to detailed;
//   - spark rejects reasoning.summary (openai/codex#31846) — omitted via quirk regex;
//   - ChatGPT backend rejects token-limit params: max_output_tokens is NEVER sent; the clamp
//     applies to REPORTED usage (P3-USE).
@file:Suppress("StringLiteralDuplication") // effort/summary alias tables are inherently literal

package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
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
    val compactEffortPin: String? = null, // null = inherit session effort (the cache law)
    val emitToolChoice: Boolean = false,
    val emitStrict: Boolean = false,
)

public enum class CacheKeyStrategy { FIRST_MESSAGE_HASH, SESSION_ID, OFF }

public enum class EffortLadder { CODEX, GROK }

public data class BuiltRequest(val req: JsonObject, val meta: TurnMeta)

public data class BuildOptions(
    val compact: Boolean,
    val originalModel: String,
    val upstreamModel: String,
    val configEffort: String?,
    val configSummary: String?,
    val showReasoning: String,
    val replayReasoning: Boolean,
    val sessionId: String? = null,
    /** Decodes a redacted_thinking envelope back into a Responses reasoning input item. */
    val decodeReasoningEnvelope: (String) -> JsonObject?,
)

@Suppress(
    "TooManyFunctions", // one helper per input-item family — the ported shape
    "StringLiteralDuplication", // wire fields are inherently literal
    "CyclomaticComplexMethod", // the knob cascades are quoted from translate-request.mjs
    "LongMethod", // build() mirrors the single build function of the source
    "NestedBlockDepth", // message/block walk is the literal port
    "ComplexCondition", // gating conditions quoted verbatim
)
public class ResponsesRequestBuilder(private val quirks: ResponsesQuirks) {

    public fun build(body: AnthropicRequest, raw: JsonObject, opts: BuildOptions): BuiltRequest {
        val input = buildJsonArray {
            for (msg in body.messages) {
                appendMessage(this, msg, opts)
            }
        }
        val instructions = compactAwareInstructions(body.system, opts.compact)
        val effort = resolveEffort(body, raw, opts)
        val summary = resolveSummary(raw, opts, effort)
        val reasoning = reasoningBlock(effort, summary, opts)

        val req = buildJsonObject {
            put("model", opts.upstreamModel)
            put("input", input)
            put("store", quirks.store)
            put("stream", true)
            if (!opts.compact && opts.replayReasoning) {
                put("include", buildJsonArray { add("reasoning.encrypted_content") })
            }
            cacheKey(body, opts)?.let { put("prompt_cache_key", it) }
            put("instructions", instructions)
            if (!opts.compact && body.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        body.tools.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", t.name)
                                    put("description", t.description ?: "")
                                    put("parameters", t.inputSchema ?: buildJsonObject { put("type", "object") })
                                    if (quirks.emitStrict && t.strict == true) put("strict", true)
                                },
                            )
                        }
                    },
                )
                if (quirks.emitToolChoice) {
                    put("tool_choice", toolChoice(body))
                    put("parallel_tool_calls", body.toolChoice?.disableParallelToolUse != true)
                }
            }
            reasoning?.let { put("reasoning", it) }
        }
        // meta.summary reflects what was ACTUALLY sent (spark drops it → "none"), like Node's
        // `req.reasoning?.summary ?? 'none'` — not the computed-but-maybe-dropped value.
        val sentSummary = (reasoning?.get("summary") as? JsonPrimitive)?.content ?: "none"

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

    // ── input items ──────────────────────────────────────────────────────────

    private fun appendMessage(
        sink: kotlinx.serialization.json.JsonArrayBuilder,
        msg: AnthropicMessage,
        opts: BuildOptions,
    ) {
        for (block in msg.content) {
            when (block) {
                is TextBlock -> sink.add(roleText(msg.role, block.text))
                is ImageBlock -> appendImage(sink, block, opts)
                is DocumentBlock -> if (!opts.compact) {
                    sink.add(
                        roleText(
                            "user",
                            "[document omitted by ${quirks.providerTag} proxy: " +
                                "${block.source?.mediaType ?: "unknown type"}]",
                        ),
                    )
                }
                is RedactedThinkingBlock -> if (!opts.compact && opts.replayReasoning) {
                    opts.decodeReasoningEnvelope(block.data)?.let { sink.add(it) }
                }
                is ToolUseBlock -> if (!opts.compact) {
                    sink.add(
                        buildJsonObject {
                            put("type", "function_call")
                            put("call_id", block.id)
                            put("name", block.name)
                            put("arguments", block.input.toString())
                        },
                    )
                }
                is ToolResultBlock -> appendToolResult(sink, block, opts)
                is ThinkingBlock -> Unit // visible thinking never rides back upstream
                is UnknownBlock -> Unit // unknown client blocks are dropped, never crash
            }
        }
    }

    private fun appendImage(sink: kotlinx.serialization.json.JsonArrayBuilder, block: ImageBlock, opts: BuildOptions) {
        if (opts.compact) return // compact is a text-only summarizer
        val part = imagePart(block.source)
        if (part != null) {
            sink.add(
                buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { add(part) })
                },
            )
        } else {
            sink.add(roleText("user", "[image omitted by ${quirks.providerTag} proxy: unsupported source]"))
        }
    }

    private fun appendToolResult(
        sink: kotlinx.serialization.json.JsonArrayBuilder,
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
            sink.add(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", "[images from tool_result ${block.toolUseId}]")
                                },
                            )
                            imageParts.forEach { add(it) }
                        },
                    )
                },
            )
        }
    }

    private fun imagePart(source: MediaSource?): JsonObject? = when {
        source == null -> null
        source.type == "base64" && !source.data.isNullOrEmpty() -> buildJsonObject {
            put("type", "input_image")
            // Node `src.media_type || 'image/png'` falls back on empty string too, not just null.
            val mime = source.mediaType?.takeIf { it.isNotEmpty() } ?: "image/png"
            put("image_url", "data:$mime;base64,${source.data}")
        }
        source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
            put("type", "input_image")
            put("image_url", source.url)
        }
        else -> null
    }

    private fun roleText(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
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
        val budgetEffort = body.thinking
            ?.takeIf { !it.disabled }
            ?.budgetTokens
            ?.let { effortFromBudget(it, quirks.effortLadder) }
        if (effort == null) {
            // v27: the /effort picker (budget) WINS over the config/env fallback
            effort = budgetEffort ?: normalizeEffort(opts.configEffort, quirks.effortLadder) ?: "high"
        }
        if (opts.showReasoning != "off" && (effort == "minimal" || effort == "none")) {
            // visibility guarantee, NOT an override: never raise a deliberate low/medium/high pick
            effort = "low"
        }
        if (quirks.effortLadder == EffortLadder.GROK) {
            // grok reasoning cannot be disabled — floor at low
            effort = effort.takeIf { it in GROK_EFFORTS } ?: "low"
        }
        return effort
    }

    // NB: `as? JsonObject` NOT `?.jsonObject` — the latter THROWS on a non-object (e.g. a client
    // sending `"reasoning":"high"` as a bare string); Node's optional chaining degrades to default.
    private fun looseEffort(raw: JsonObject): String? = sequenceOf(
        raw["effort"],
        raw["reasoning_effort"],
        (raw["output_config"] as? JsonObject)?.get("effort"),
        (raw["metadata"] as? JsonObject)?.get("effort"),
        (raw["reasoning"] as? JsonObject)?.get("effort"),
    ).mapNotNull { (it as? JsonPrimitive)?.content }
        .mapNotNull { normalizeEffort(it, quirks.effortLadder) }
        .firstOrNull()

    private fun resolveSummary(raw: JsonObject, opts: BuildOptions, effort: String?): String? {
        if (!quirks.supportsSummary || effort == null) return null
        var summary = sequenceOf(
            (raw["reasoning"] as? JsonObject)?.get("summary"),
            raw["reasoning_summary"],
            (raw["output_config"] as? JsonObject)?.get("reasoning_summary"),
        ).mapNotNull { (it as? JsonPrimitive)?.content }
            .mapNotNull { normalizeSummary(it) }
            .firstOrNull()
            ?: normalizeSummary(opts.configSummary)
            ?: "detailed"
        if (opts.showReasoning != "off" && summary in setOf("none", "auto", "concise")) {
            summary = "detailed" // reliably fills summary_text; effort alone can leave it empty
        }
        return summary
    }

    private fun reasoningBlock(effort: String?, summary: String?, opts: BuildOptions): JsonObject? {
        if (effort == null) return null
        val dropSummary = quirks.summaryRejectModelRegex?.containsMatchIn(opts.upstreamModel) == true ||
            summary == null || summary == "none"
        return buildJsonObject {
            put("effort", effort)
            if (!dropSummary) put("summary", summary)
        }
    }

    private fun toolChoice(body: AnthropicRequest): kotlinx.serialization.json.JsonElement {
        val choice = body.toolChoice
        return when {
            choice == null || choice.type == "auto" -> JsonPrimitive("auto")
            choice.type == "none" -> JsonPrimitive("none")
            choice.type == "any" -> JsonPrimitive("required")
            choice.type == "tool" && choice.name != null -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", choice.name) })
            }
            else -> JsonPrimitive("auto")
        }
    }

    private companion object {
        val GROK_EFFORTS = setOf("low", "medium", "high")
    }
}

/** Compact-mode model+effort pinning helper: empty compactModel = session model (the cache law). */
public fun compactUpstreamModel(compact: Boolean, compactModel: String?, sessionModel: String): String =
    if (compact && !compactModel.isNullOrEmpty()) compactModel else sessionModel

/** Codex-parity cache key: sha256 of the FIRST user message's text, stable per conversation. */
public fun stablePromptCacheKey(body: AnthropicRequest): String? {
    val first = body.messages.firstOrNull { it.role == "user" } ?: return null
    val seed = first.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    if (seed.isEmpty()) return null
    val hash = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    return "splice-" + hash.joinToString("") { "%02x".format(it) }.take(HASH_PREFIX_LEN)
}

private const val HASH_PREFIX_LEN = 32

@Suppress("CyclomaticComplexMethod") // the alias table IS the contract
public fun normalizeEffort(raw: String?, ladder: EffortLadder): String? {
    val s = raw?.trim()?.lowercase().orEmpty()
    if (s.isEmpty()) return null
    return when (ladder) {
        EffortLadder.CODEX -> when (s) {
            "ultracode", "ultra" -> "max"
            "extra_high", "extra-high", "extrahigh" -> "xhigh"
            "standard", "normal" -> "medium"
            "light", "fast" -> "low"
            "heavy", "extended" -> "high"
            in CODEX_EFFORTS -> s
            else -> null
        }
        EffortLadder.GROK -> when (s) {
            "high", "xhigh", "max", "ultra", "ultracode", "extra_high", "extra-high",
            "extrahigh", "heavy", "extended",
            -> "high"
            "medium", "standard", "normal" -> "medium"
            "low", "minimal", "none", "off", "fast", "light" -> "low"
            else -> null
        }
    }
}

private val CODEX_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

public fun normalizeSummary(raw: String?): String? {
    val s = raw?.trim()?.lowercase().orEmpty()
    return when {
        s.isEmpty() -> null
        s in setOf("auto", "concise", "detailed", "none") -> s
        s in setOf("full", "verbose", "long") -> "detailed"
        s in setOf("short", "brief") -> "concise"
        s in setOf("off", "false", "0") -> "none"
        else -> null
    }
}

@Suppress("CyclomaticComplexMethod") // tier table
public fun effortFromBudget(budget: Long, ladder: EffortLadder): String? = when (ladder) {
    EffortLadder.CODEX -> when {
        budget >= BUDGET_MAX -> "max"
        budget >= BUDGET_XHIGH -> "xhigh"
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> "medium"
        else -> "low"
    }
    EffortLadder.GROK -> when {
        budget >= BUDGET_HIGH -> "high"
        budget >= BUDGET_MEDIUM -> "medium"
        else -> "low"
    }
}

private const val BUDGET_MAX = 64_000L
private const val BUDGET_XHIGH = 32_000L
private const val BUDGET_HIGH = 10_000L
private const val BUDGET_MEDIUM = 2_000L
