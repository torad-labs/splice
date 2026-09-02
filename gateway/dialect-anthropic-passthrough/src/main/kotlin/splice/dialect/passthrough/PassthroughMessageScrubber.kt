// NEW: the message content-block scrubbing family — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). Six functions that only call each other, the allowlist and
// the stripper — the largest self-contained cluster in the file, and the only one that reads
// quirks.blockAllowlist. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

internal class PassthroughMessageScrubber(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
) {

    fun scrubMessages(messages: JsonElement): JsonArray {
        val arr = messages as? JsonArray ?: return buildJsonArray { }
        return buildJsonArray {
            arr.forEach { msg -> (msg as? JsonObject)?.let { add(scrubMessage(it)) } }
        }
    }

    private fun scrubMessage(msg: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in msg) {
            if (key == CONTENT) put(CONTENT, scrubContent(value)) else put(key, cache.stripCacheControl(value))
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
        val type = JsonScalars.strOrEmpty(block["type"])
        quirks.blockAllowlist?.let { if (type !in it) return null }
        if (isEmptyThinking(type, block)) return null
        return rebuildBlock(block, type)
    }

    /** A whitespace-only thinking block that carries no signature holds nothing worth keeping. */
    private fun isEmptyThinking(type: String, block: JsonObject): Boolean {
        if (type != TYPE_THINKING) return false
        return JsonScalars.strOrEmpty(block["thinking"]).isBlank() &&
            JsonScalars.strOrEmpty(block["signature"]).isEmpty()
    }

    private fun rebuildBlock(block: JsonObject, type: String): JsonObject = buildJsonObject {
        for ((key, value) in block) {
            when {
                key == CACHE_CONTROL && quirks.stripCacheControl -> Unit
                key == CONTENT && type == TYPE_TOOL_RESULT -> put(CONTENT, scrubContent(value))
                else -> put(key, cache.stripCacheControl(value))
            }
        }
    }
}
