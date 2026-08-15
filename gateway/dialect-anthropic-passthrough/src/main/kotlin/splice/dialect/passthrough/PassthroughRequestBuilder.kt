// NEW: (no Node source) Anthropic Messages -> an Anthropic-surface upstream. The body is preserved
// (unknown fields ride through verbatim) and only these happen unconditionally: `model` is retargeted, `stream` is
// forced, compact turns drop tools + tool_choice, and (CX-02, 2026-08-10) the compaction directive
// is appended to `system` on a compact turn — without which a compaction turn is an ordinary
// tool-stripped turn and a chatty reply is stored silently as the session summary.
//
// EVERY OTHER TRANSFORM IS A DECLARED QUIRK, OFF BY DEFAULT (see PassthroughQuirks): cache_control
// stripping, the content-block allowlist, MFJS schema rewriting, and the adaptive-thinking +
// output_config.effort ladder. They were hardcoded while Kimi was the only consumer; a faithful
// upstream (api.anthropic.com) needs its prompt-cache markers, full JSON Schema, and its own
// thinking config to survive the trip. `PassthroughQuirks.kimi(tag)` is the one definition of
// Kimi's set, and its bytes are frozen by PassthroughGoldenTest.
//
// Invariants that hold for every head: thinking blocks pass VERBATIM (signature included), and the
// effort ladder never emits "medium" (Kimi vocab is low|high|max).
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.compactDirective
import splice.core.turn.withCompactDirective
import splice.core.util.strOrEmpty
import splice.core.wire.AnthropicRequest

/**
 * The knobs that turn a FAITHFUL Anthropic passthrough into one vendor's accepted shape.
 *
 * DEFAULTS ARE NEUTRAL, and that inversion is the point (campaign claude-head, CH-2). Every knob
 * below was hardcoded ON when Kimi was the dialect's only consumer, which made "passthrough" a
 * misnomer: a real Anthropic upstream loses prompt caching to [stripCacheControl], has its tool
 * schemas rewritten by [mfjsSanitize], has `redacted_thinking` silently dropped by
 * [blockAllowlist], and can be handed a forged thinking signature by [synthesizeSignatures] that
 * a signature-VERIFYING upstream later rejects. A vendor now opts INTO its own deformations
 * ([kimi] is the one definition of Kimi's set); a head that declares nothing gets its bytes
 * forwarded as sent.
 */
public data class PassthroughQuirks(
    val providerTag: String,
    /** Kimi's Anthropic surface accepts ONLY adaptive-style thinking for effort control;
     *  budget-based inference fails for Kimi model ids. Neutral forwards `thinking` verbatim
     *  (and stops owning `output_config`, so a client's own rides through). */
    val mapThinkingToAdaptive: Boolean = false,
    /** Compact-turn effort pin. null (the default) = compact INHERITS the session's own effort
     *  (v27 doctrine: compact turns inherit the session's model AND effort — a mismatch on either
     *  invalidates the prompt cache and re-reads the whole transcript cold). Set ONLY to
     *  deliberately pin a provider whose compact cost dominates. */
    val compactEffort: String? = null,
    /** Drop temperature/top_p/top_k when a live probe shows the endpoint rejects them. */
    val stripSamplingParams: Boolean = false,
    /** Rewrite tool `input_schema` into Moonshot-Flavored JSON Schema (and drop `strict` / invent
     *  an empty `description`). An upstream that accepts full JSON Schema must leave this OFF:
     *  the sanitizer discards `format`, `prefixItems`, `$ref` siblings and tuple `items`, which
     *  CHANGES tool semantics. */
    val mfjsSanitize: Boolean = false,
    /** Content-block types the upstream accepts; every other block is DROPPED. null (neutral) =
     *  every block rides. Kimi's list comes from its own 400 and excludes `redacted_thinking`,
     *  `document` and `search_result` — silent content loss against an upstream that accepts them. */
    val blockAllowlist: Set<String>? = null,
    /** Deep-strip every `cache_control` marker. Neutral PRESERVES them: against an upstream with
     *  prompt caching, stripping is a silent cold-read on every turn, not an error. */
    val stripCacheControl: Boolean = false,
    /** Synthesize ONE thinking-block signature at close when the upstream sent none. Required for
     *  Kimi (never signs; Claude Code discards unsigned thinking blocks) and WRONG for an upstream
     *  that signs and verifies — a truncated block would otherwise persist a forged signature into
     *  the transcript and return it upstream on the next turn. */
    val synthesizeSignatures: Boolean = false,
) {
    public companion object {
        /** KIMI's deformation set — the shape that was hardcoded before the inversion. ONE
         *  definition, so provider wiring and the byte-identity goldens cannot drift apart. */
        public fun kimi(providerTag: String): PassthroughQuirks = PassthroughQuirks(
            providerTag = providerTag,
            mapThinkingToAdaptive = true,
            mfjsSanitize = true,
            blockAllowlist = KIMI_BLOCK_TYPES,
            stripCacheControl = true,
            synthesizeSignatures = true,
        )

        /** Kimi's own 400 enumerates the accepted content tags; everything else is dropped. */
        public val KIMI_BLOCK_TYPES: Set<String> = setOf(
            "text",
            "image",
            TYPE_THINKING,
            "tool_use",
            TYPE_TOOL_RESULT,
            "server_tool_use",
            "web_search_tool_result",
        )
    }
}

private const val TYPE_THINKING = "thinking"
private const val TYPE_TOOL_RESULT = "tool_result"

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
            compactAwareSystem(raw[SYSTEM], compact)?.let { put(SYSTEM, it) }
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
            val dropped = key in handledKeys || (key in SAMPLING_KEYS && quirks.stripSamplingParams)
            if (!dropped) put(key, stripCacheControl(value))
        }
    }

    /** `output_config` is owned by the thinking mapping ONLY when that mapping is on; a neutral
     *  passthrough has no business dropping a field the client chose to send. */
    /** Tool keys this head drops outright — fixed by the quirks, so computed once. */
    private val droppedToolKeys: Set<String> = buildSet {
        if (quirks.mfjsSanitize) add(STRICT)
        if (quirks.stripCacheControl) add(CACHE_CONTROL)
    }

    private val handledKeys: Set<String> =
        if (quirks.mapThinkingToAdaptive) HANDLED_KEYS else HANDLED_KEYS - OUTPUT_CONFIG

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
        val type = strOrEmpty(block["type"])
        quirks.blockAllowlist?.let { if (type !in it) return null }
        if (isEmptyThinking(type, block)) return null
        return rebuildBlock(block, type)
    }

    /** A whitespace-only thinking block that carries no signature holds nothing worth keeping. */
    private fun isEmptyThinking(type: String, block: JsonObject): Boolean {
        if (type != TYPE_THINKING) return false
        return strOrEmpty(block["thinking"]).isBlank() && strOrEmpty(block["signature"]).isEmpty()
    }

    private fun rebuildBlock(block: JsonObject, type: String): JsonObject = buildJsonObject {
        for ((key, value) in block) {
            when {
                key == CACHE_CONTROL && quirks.stripCacheControl -> Unit
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
            when {
                key in droppedToolKeys -> Unit
                key == INPUT_SCHEMA && quirks.mfjsSanitize ->
                    put(INPUT_SCHEMA, MfjsSanitizer.sanitize(value as? JsonObject ?: EMPTY_OBJECT))
                else -> put(key, stripCacheControl(value))
            }
        }
        // Kimi 400s a tool with no description; inventing one on a faithful passthrough would be
        // splice putting words in the client's request, so it rides with the schema shaping.
        if (quirks.mfjsSanitize && DESCRIPTION !in tool) put(DESCRIPTION, "")
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** CX-02: the scrubbed system field, with the compaction directive appended on a compact turn.
     *
     *  This is the one place passthrough INVENTS content rather than forwarding it, and it is
     *  deliberate: without it a kimi compaction turn is an ordinary tool-stripped turn and a chatty
     *  reply becomes the stored summary. Both legal shapes are handled — Anthropic's `system` is a
     *  string OR an array of blocks — and a compact turn with no system at all still gets one.
     *  Non-compact returns exactly what the old `stripCacheControl` call returned, null included. */
    private fun compactAwareSystem(system: JsonElement?, compact: Boolean): JsonElement? {
        val scrubbed = system?.let { stripCacheControl(it) }
        if (!compact) return scrubbed
        val directiveBlock = buildJsonObject {
            put("type", "text")
            put("text", compactDirective)
        }
        return when (scrubbed) {
            is JsonArray -> buildJsonArray {
                scrubbed.forEach { add(it) }
                add(directiveBlock)
            }
            null -> buildJsonArray { add(directiveBlock) }
            // A string system prompt stays a string — appending a block would change its type.
            // strOrEmpty returns "" for any NON-primitive (an object, JSON null), which in a
            // verbatim-forwarding dialect would silently replace a client's unusual-but-forwardable
            // system with the directive alone. Forward it untouched instead and append the
            // directive as its own block, so nothing the client sent is ever dropped.
            is JsonPrimitive -> JsonPrimitive(withCompactDirective(strOrEmpty(scrubbed), compact = true))
            else -> buildJsonArray {
                add(scrubbed)
                add(directiveBlock)
            }
        }
    }

    /** Recursively remove every `cache_control` key; other structure passes verbatim. Bounded by
     *  [DEPTH_CAP] (mirrors MfjsSanitizer's guard): client-supplied JSON deeper than the cap is
     *  passed through AS-IS beyond that point — cache_control stripping at extreme depth is
     *  immaterial, and this must never StackOverflow on adversarially nested input. */
    private fun stripCacheControl(element: JsonElement, depth: Int = 0): JsonElement {
        if (!quirks.stripCacheControl) return element
        if (depth >= DEPTH_CAP) return element
        return when (element) {
            is JsonObject -> buildJsonObject {
                for ((key, value) in element) {
                    if (key != CACHE_CONTROL) put(key, stripCacheControl(value, depth + 1))
                }
            }
            is JsonArray -> buildJsonArray { element.forEach { add(stripCacheControl(it, depth + 1)) } }
            else -> element
        }
    }

    private companion object {
        // stripCacheControl's recursion guard (WIRE-1) — far above any legitimate request's
        // nesting, well below a stack-overflow depth.
        const val DEPTH_CAP = 200
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
        val EMPTY_OBJECT = JsonObject(emptyMap())
    }
}
