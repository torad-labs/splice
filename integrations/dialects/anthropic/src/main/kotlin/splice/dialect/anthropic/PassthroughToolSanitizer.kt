// NEW: the tool-schema quirks wrapper — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). Sits beside MfjsSanitizer.kt, which already owns the other half of this
// exact responsibility. NOT folded into MfjsSanitizer.kt — that object is a pure, quirks-unaware
// schema rewrite with its own DEPTH_CAP and its own TYPE/ITEMS constant namespace; folding the
// quirks-aware wrapper in would re-create the "collaborator in the same file" pattern this
// campaign exists to undo. Every relocated member kept its identical name and argument list.
//
// 2026-10-27: on a head that cannot carry the block that loads a deferred tool, the tool list loads it. Claude Code
// sends each deferred tool's definition with `defer_loading: true` and loads one by answering ToolSearch with a
// `tool_reference` that names it. Kimi, like Anthropic, keeps a deferred tool out of the model's view until such a
// reference loads it, but Kimi's allowlist excludes the block, so PassthroughMessageScrubber writes
// "[tool loaded: X. It can be called directly.]" in its place and the tool stayed hidden: across 400 claude-kimi
// turns, 40 ToolSearch calls and not one call to a tool they loaded. So on such a head a tool that a reference in
// the conversation names rides without `defer_loading`, and every other deferred tool stays deferred. The list
// changes on the turn the tool loads, which misses Kimi's prompt cache once; later turns send the same list for as
// long as the conversation holds the reference.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.wire.UnknownBlock
import splice.upstream.ToolNameShortener

internal class PassthroughToolSanitizer(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
    private val names: ToolNameShortener = ToolNameShortener(),
) {

    /** Tool keys this head drops outright — fixed by the quirks, so computed once. */
    private val droppedToolKeys: Set<String> = buildSet {
        if (quirks.request.mfjsSanitize) add(STRICT)
        if (quirks.request.stripCacheControl) add(CACHE_CONTROL)
    }

    /** What a tool the conversation loaded drops: the same keys, and the deferral its reference lifted. */
    private val loadedToolKeys: Set<String> = droppedToolKeys + DEFER_LOADING

    /** This head's upstream never reads the `tool_reference` that loads a deferred tool, so the list loads it. */
    private val listLoadsTools: Boolean = quirks.request.blockAllowlist?.let { TOOL_REFERENCE !in it } == true

    /** [conversation] is the request's `messages`. It is read only where [listLoadsTools]: there a deferred tool a
     *  reference in it names rides without `defer_loading`, since the model is told that tool is loaded. */
    fun sanitizeTools(tools: JsonElement, clientStreams: Boolean, conversation: JsonElement? = null): JsonArray {
        val arr = tools as? JsonArray ?: return buildJsonArray { }
        val loaded = if (listLoadsTools) loadedTools(conversation) else emptySet()
        return buildJsonArray {
            arr.forEach { tool -> (tool as? JsonObject)?.let { add(sanitizeTool(it, clientStreams, loaded)) } }
        }
    }

    private fun sanitizeTool(tool: JsonObject, clientStreams: Boolean, loaded: Set<String>): JsonObject =
        buildJsonObject {
            // A reference holds the client's name, so a loaded tool is found before V4-32 shortens it.
            val dropped = if (JsonScalars.strOrEmpty(tool[NAME]) in loaded) loadedToolKeys else droppedToolKeys
            for ((key, value) in tool) {
                if (key in dropped) continue
                put(key, sanitizedValue(key, value))
            }
            // Kimi 400s a tool with no description; inventing one on a faithful passthrough would be
            // splice putting words in the client's request, so it rides with the schema shaping.
            if (quirks.request.mfjsSanitize && DESCRIPTION !in tool) put(DESCRIPTION, "")
            if (eagerInputDefault(tool, clientStreams)) put(EAGER_INPUT_STREAMING, true)
        }

    /** The client's names of the tools [conversation] loaded: each `tool_reference` whose name is a plain tool name
     *  ([UnknownBlock.loadedTool]), among a message's blocks or inside a tool_result, which is everywhere the scrubber
     *  turns one into "[tool loaded: X ...]". A worklist, not recursion: the client chooses how deep results nest. */
    private fun loadedTools(conversation: JsonElement?): Set<String> {
        val pending = ArrayDeque<JsonElement?>()
        (conversation as? JsonArray)?.forEach { message -> pending.add((message as? JsonObject)?.get(CONTENT)) }
        return buildSet {
            while (pending.isNotEmpty()) {
                (pending.removeLast() as? JsonArray)?.forEach { element ->
                    val block = element as? JsonObject ?: return@forEach
                    UnknownBlock(block).loadedTool?.let { add(it) }
                    if (JsonScalars.strOrEmpty(block[TYPE]) == TYPE_TOOL_RESULT) pending.add(block[CONTENT])
                }
            }
        }
    }

    private fun eagerInputDefault(tool: JsonObject, clientStreams: Boolean): Boolean {
        val enabled = clientStreams && quirks.request.eagerToolInputs
        if (!enabled || EAGER_INPUT_STREAMING in tool) return false
        val type = tool["type"]
        return type == null || type == JsonPrimitive("custom")
    }

    /** Split from [sanitizeTool] so the per-key decision does not push the loop over detekt's
     *  cyclomatic threshold — the V4-32 name rewrite was the branch that tipped it. */
    private fun sanitizedValue(key: String, value: JsonElement): JsonElement = when {
        key == INPUT_SCHEMA && quirks.request.mfjsSanitize ->
            MfjsSanitizer.sanitize(value as? JsonObject ?: EMPTY_OBJECT)
        // V4-32: the declaration the upstream validates. Shortened here, restored on the
        // response side by PassthroughBlockRegistry; see ToolNameShortener.
        key == NAME -> JsonPrimitive(names.shorten(JsonScalars.strOrEmpty(value)))
        else -> cache.stripCacheControl(value)
    }
}

private const val STRICT = "strict"
private const val INPUT_SCHEMA = "input_schema"
private const val DESCRIPTION = "description"
private const val EAGER_INPUT_STREAMING = "eager_input_streaming"
private const val DEFER_LOADING = "defer_loading"
private const val TOOL_REFERENCE = "tool_reference"

// FILE SCOPE ON PURPOSE: one shared empty object read on the tool-sanitizing path; as a member it
// would be rebuilt per builder instance.
private val EMPTY_OBJECT = JsonObject(emptyMap())
