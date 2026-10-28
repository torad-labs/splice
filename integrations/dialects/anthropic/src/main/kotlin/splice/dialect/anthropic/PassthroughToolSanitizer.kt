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
// reference loads it. Kimi's old allowlist excluded the block, so PassthroughMessageScrubber wrote
// "[tool loaded: X. It can be called directly.]" in its place and the tool stayed hidden: across 400 claude-kimi
// turns, 40 ToolSearch calls and not one call to a tool they loaded. So on such a head a tool that a reference in
// the conversation names rides without `defer_loading`, and every other deferred tool stays deferred. The list
// changes on the turn the tool loads, which misses Kimi's prompt cache once; later turns send the same list for as
// long as the conversation holds the reference.
// Kimi now carries its native references and avoids that cache miss. This fallback is only for upstreams whose
// allowlist still excludes references; never drop a supported reference just to load its tool through the list.
//
// Later the same day: the tool_addition and tool_removal blocks are carried the same way. Kimi's allowlist drops both,
// so the tools Claude Code added never reached the model and the ones it removed stayed offered. Where the upstream
// cannot read them, an added tool rides in the list and a removed one leaves it (PassthroughToolTimeline), at one
// cache miss per change.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
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

    /** The tool changes this head's upstream cannot read from the conversation, so its list carries them. */
    private val timeline = PassthroughToolTimeline(quirks.request.blockAllowlist)

    /** The list this head sends for the client's [tools], or null when the client sent none and the conversation
     *  added none. [conversation] is the request's `messages`: where the upstream cannot read a block that changed the
     *  tools ([PassthroughToolTimeline]), a tool it added rides with the definition it last gave, a tool it removed
     *  leaves, and a deferred tool a reference loaded rides without `defer_loading`, since the model is told it is
     *  loaded. A conversation that changes nothing sends the client's tools as this head shapes them. */
    fun sanitizeTools(tools: JsonElement?, clientStreams: Boolean, conversation: JsonElement? = null): JsonArray? {
        val sent = (tools as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val changes = timeline.changes(sent.map { JsonScalars.strOrEmpty(it[NAME]) }, conversation)
        if (tools == null && changes.added.isEmpty()) return null
        // A change holds the client's name, so each tool is matched before V4-32 shortens it.
        val offered = sent.map { tool ->
            val name = JsonScalars.strOrEmpty(tool[NAME])
            name to (changes.definitions[name] ?: tool)
        } + changes.added.map { name -> name to changes.definitions.getValue(name) }
        return buildJsonArray {
            offered.filterNot { (name, _) -> name in changes.withdrawn }.forEach { (name, tool) ->
                add(sanitizeTool(tool, clientStreams, loaded = name in changes.loaded))
            }
        }
    }

    private fun sanitizeTool(tool: JsonObject, clientStreams: Boolean, loaded: Boolean): JsonObject =
        buildJsonObject {
            val dropped = if (loaded) loadedToolKeys else droppedToolKeys
            for ((key, value) in tool) {
                if (key in dropped) continue
                put(key, sanitizedValue(key, value))
            }
            // Kimi 400s a tool with no description; inventing one on a faithful passthrough would be
            // splice putting words in the client's request, so it rides with the schema shaping.
            if (quirks.request.mfjsSanitize && DESCRIPTION !in tool) put(DESCRIPTION, "")
            if (eagerInputDefault(tool, clientStreams)) put(EAGER_INPUT_STREAMING, true)
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

// FILE SCOPE ON PURPOSE: one shared empty object read on the tool-sanitizing path; as a member it
// would be rebuilt per builder instance.
private val EMPTY_OBJECT = JsonObject(emptyMap())
