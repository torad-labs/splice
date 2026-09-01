// NEW: the tool-schema quirks wrapper — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). Sits beside MfjsSanitizer.kt, which already owns the other half of this
// exact responsibility. NOT folded into MfjsSanitizer.kt — that object is a pure, quirks-unaware
// schema rewrite with its own DEPTH_CAP and its own TYPE/ITEMS constant namespace; folding the
// quirks-aware wrapper in would re-create the "collaborator in the same file" pattern this
// campaign exists to undo. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PassthroughToolSanitizer(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
) {

    /** Tool keys this head drops outright — fixed by the quirks, so computed once. */
    private val droppedToolKeys: Set<String> = buildSet {
        if (quirks.mfjsSanitize) add(STRICT)
        if (quirks.stripCacheControl) add(CACHE_CONTROL)
    }

    fun sanitizeTools(tools: JsonElement): JsonArray {
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
                else -> put(key, cache.stripCacheControl(value))
            }
        }
        // Kimi 400s a tool with no description; inventing one on a faithful passthrough would be
        // splice putting words in the client's request, so it rides with the schema shaping.
        if (quirks.mfjsSanitize && DESCRIPTION !in tool) put(DESCRIPTION, "")
    }
}

private const val STRICT = "strict"
private const val INPUT_SCHEMA = "input_schema"
private const val DESCRIPTION = "description"

// FILE SCOPE ON PURPOSE: one shared empty object read on the tool-sanitizing path; as a member it
// would be rebuilt per builder instance.
private val EMPTY_OBJECT = JsonObject(emptyMap())
