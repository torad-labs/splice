// NEW: (no Node source) near-identity Anthropic Messages -> Kimi Anthropic-surface request. Kimi's
// /coding endpoint speaks the Anthropic wire, so this builder preserves the RAW body (unknown
// fields ride through verbatim) and only SCRUBS the handful of shapes Kimi rejects: it retargets
// `model`, forces `stream`, deep-strips every `cache_control`, filters content/tool_result blocks
// to Kimi's accepted tag allowlist, runs tool input_schema through the MFJS sanitizer, and remaps
// Anthropic thinking config into Kimi's adaptive-thinking + output_config.effort ladder. Compact
// turns additionally drop tools + tool_choice (splice compaction doctrine). Invariants: no field is
// invented except the adaptive thinking/output_config pair; thinking blocks pass VERBATIM (signature
// included); the effort ladder never emits "medium" (Kimi vocab is low|high|max).
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest

public data class PassthroughQuirks(
    val providerTag: String,
    /** Kimi's Anthropic surface accepts ONLY adaptive-style thinking for effort control;
     *  budget-based inference fails for Kimi model ids. */
    val mapThinkingToAdaptive: Boolean = true,
    /** Compact-turn effort pin. null (the default) = compact INHERITS the session's own effort
     *  (v27 doctrine: compact turns inherit the session's model AND effort — a mismatch on either
     *  invalidates the prompt cache and re-reads the whole transcript cold). Set ONLY to
     *  deliberately pin a provider whose compact cost dominates. */
    val compactEffort: String? = null,
    /** Drop temperature/top_p/top_k when a live probe shows the endpoint rejects them. */
    val stripSamplingParams: Boolean = false,
)

public data class BuiltPassthroughRequest(val req: JsonObject, val meta: TurnMeta)

public class PassthroughRequestBuilder(private val quirks: PassthroughQuirks) {

    public fun build(
        body: AnthropicTurnBody,
        upstreamModel: String,
        originalModel: String,
        compact: Boolean,
    ): BuiltPassthroughRequest {
        val raw = body.raw
        val typed = body.typed
        val effort = effortLadder(typed, compact)

        val req = buildJsonObject {
            copyUnhandledFields(raw)
            put(MODEL, upstreamModel)
            put(STREAM, true)
            raw[SYSTEM]?.let { put(SYSTEM, stripCacheControl(it)) }
            raw[MESSAGES]?.let { put(MESSAGES, scrubMessages(it)) }
            if (!compact) {
                raw[TOOLS]?.let { put(TOOLS, sanitizeTools(it)) }
                raw[TOOL_CHOICE]?.let { put(TOOL_CHOICE, stripCacheControl(it)) }
            }
            putThinking(typed, raw[THINKING], effort)
        }

        val meta = TurnMeta(
            compact = compact,
            // Passthrough emits REAL thinking blocks; the text mirror must NOT double-render them,
            // so pick the showReasoning value that makes mirrorInto a no-op (any value != "text").
            showReasoning = ReasoningDisplay.THINKING,
            stream = typed.stream,
            originalModel = originalModel,
            upstreamModel = upstreamModel,
            clientMaxTokens = typed.maxTokens?.takeIf { it > 0 },
            effort = effort,
            summary = null,
            budgetTokens = typed.thinking?.budgetTokens,
        )
        return BuiltPassthroughRequest(req, meta)
    }

    /** Copy every field the specialized scrubs do NOT own, cache_control stripped; sampling
     *  params optionally dropped. Unknown client fields ride through here verbatim. */
    private fun JsonObjectBuilder.copyUnhandledFields(raw: JsonObject) {
        for ((key, value) in raw) {
            val dropped = key in HANDLED_KEYS || (key in SAMPLING_KEYS && quirks.stripSamplingParams)
            if (!dropped) put(key, stripCacheControl(value))
        }
    }

    // --- thinking mapping ------------------------------------------------------------------------

    private fun JsonObjectBuilder.putThinking(
        typed: AnthropicRequest,
        rawThinking: JsonElement?,
        effort: String,
    ) {
        val thinking = typed.thinking ?: return // absent -> omit both keys
        if (thinking.disabled) return // disabled -> OMIT thinking (never send type:"disabled")
        if (!quirks.mapThinkingToAdaptive) {
            // Fallback: forward the raw thinking config verbatim (cache_control scrubbed).
            rawThinking?.let { put(THINKING, stripCacheControl(it)) }
            return
        }
        put(
            THINKING,
            buildJsonObject {
                put("type", "adaptive")
                put("display", "summarized")
            },
        )
        put(OUTPUT_CONFIG, buildJsonObject { put("effort", effort) })
    }

    /** Kimi effort ladder — vocab is low|high|max (NO medium). Compact turns take the SAME
     *  derivation as session turns (inherit; v27) unless a pin is explicitly configured. */
    private fun effortLadder(typed: AnthropicRequest, compact: Boolean): String {
        if (compact) quirks.compactEffort?.let { return it }
        val budget = typed.thinking?.budgetTokens ?: return EFFORT_MAX
        return when {
            budget >= MAX_BUDGET_FLOOR -> EFFORT_MAX
            budget >= HIGH_BUDGET_FLOOR -> EFFORT_HIGH
            budget > 0L -> EFFORT_LOW
            else -> EFFORT_MAX
        }
    }

    // --- content-block scrubbing -----------------------------------------------------------------

    private fun scrubMessages(messages: JsonElement): JsonArray {
        val arr = messages as? JsonArray ?: return buildJsonArray { }
        return buildJsonArray {
            arr.forEach { msg -> (msg as? JsonObject)?.let { add(scrubMessage(it)) } }
        }
    }

    private fun scrubMessage(msg: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in msg) {
            if (key == CONTENT) put(CONTENT, scrubContent(value)) else put(key, stripCacheControl(value))
        }
    }

    /** A content value is a bare string (verbatim) or a block list (allowlist-filtered). */
    private fun scrubContent(content: JsonElement): JsonElement = when (content) {
        is JsonArray -> buildJsonArray {
            content.forEach { el -> (el as? JsonObject)?.let { scrubBlock(it) }?.let { add(it) } }
        }
        else -> content
    }

    /** Keep an accepted block (cache_control stripped, tool_result inner content filtered) or drop. */
    private fun scrubBlock(block: JsonObject): JsonObject? {
        val type = str(block["type"])
        if (type !in ALLOWED_BLOCK_TYPES) return null
        if (isEmptyThinking(type, block)) return null
        return rebuildBlock(block, type)
    }

    /** A whitespace-only thinking block that carries no signature holds nothing worth keeping. */
    private fun isEmptyThinking(type: String, block: JsonObject): Boolean {
        if (type != TYPE_THINKING) return false
        return str(block["thinking"]).isBlank() && str(block["signature"]).isEmpty()
    }

    private fun rebuildBlock(block: JsonObject, type: String): JsonObject = buildJsonObject {
        for ((key, value) in block) {
            when {
                key == CACHE_CONTROL -> Unit
                key == CONTENT && type == TYPE_TOOL_RESULT -> put(CONTENT, scrubContent(value))
                else -> put(key, stripCacheControl(value))
            }
        }
    }

    // --- tools -----------------------------------------------------------------------------------

    private fun sanitizeTools(tools: JsonElement): JsonArray {
        val arr = tools as? JsonArray ?: return buildJsonArray { }
        return buildJsonArray {
            arr.forEach { tool -> (tool as? JsonObject)?.let { add(sanitizeTool(it)) } }
        }
    }

    private fun sanitizeTool(tool: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in tool) {
            when (key) {
                STRICT, CACHE_CONTROL -> Unit
                INPUT_SCHEMA -> put(INPUT_SCHEMA, MfjsSanitizer.sanitize(value as? JsonObject ?: EMPTY_OBJECT))
                else -> put(key, stripCacheControl(value))
            }
        }
        if (DESCRIPTION !in tool) put(DESCRIPTION, "")
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Recursively remove every `cache_control` key; other structure passes verbatim. */
    private fun stripCacheControl(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((key, value) in element) if (key != CACHE_CONTROL) put(key, stripCacheControl(value))
        }
        is JsonArray -> buildJsonArray { element.forEach { add(stripCacheControl(it)) } }
        else -> element
    }

    // JsonNull IS a JsonPrimitive whose `.content` is "null"; treat an explicit null as absent.
    private fun str(element: JsonElement?): String =
        (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""

    private companion object {
        const val MODEL = "model"
        const val STREAM = "stream"
        const val THINKING = "thinking"
        const val OUTPUT_CONFIG = "output_config"
        const val MESSAGES = "messages"
        const val SYSTEM = "system"
        const val TOOLS = "tools"
        const val TOOL_CHOICE = "tool_choice"
        const val TEMPERATURE = "temperature"
        const val TOP_P = "top_p"
        const val TOP_K = "top_k"
        const val CONTENT = "content"
        const val CACHE_CONTROL = "cache_control"
        const val STRICT = "strict"
        const val INPUT_SCHEMA = "input_schema"
        const val DESCRIPTION = "description"

        const val TYPE_THINKING = "thinking"
        const val TYPE_TOOL_RESULT = "tool_result"

        // Passthrough emits native thinking blocks, so the transcript text-mirror stays off.

        const val EFFORT_LOW = "low"
        const val EFFORT_HIGH = "high"
        const val EFFORT_MAX = "max"
        const val HIGH_BUDGET_FLOOR = 8_192L
        const val MAX_BUDGET_FLOOR = 24_576L

        // Fields the specialized scrubs own (skipped by the verbatim copy); output_config is owned
        // by the thinking mapping, so a client-sent one is dropped.
        val HANDLED_KEYS = setOf(
            MODEL,
            STREAM,
            THINKING,
            OUTPUT_CONFIG,
            MESSAGES,
            SYSTEM,
            TOOLS,
            TOOL_CHOICE,
        )
        val SAMPLING_KEYS = setOf(TEMPERATURE, TOP_P, TOP_K)

        // Kimi's own 400 enumerates the accepted content tags; everything else is dropped.
        val ALLOWED_BLOCK_TYPES = setOf(
            "text",
            "image",
            TYPE_THINKING,
            "tool_use",
            TYPE_TOOL_RESULT,
            "server_tool_use",
            "web_search_tool_result",
        )
        val EMPTY_OBJECT = JsonObject(emptyMap())
    }
}
