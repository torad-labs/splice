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
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap

internal class PassthroughMessageScrubber(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
    private val names: ToolNameShortener = ToolNameShortener(),
    /** The anomaly channel for [PassthroughQuirks.blockAllowlist]. An allowlist drop is CONTENT
     *  LOSS with no other symptom: the upstream answers normally about the text it did receive, so
     *  a pasted screenshot or an attached document simply has no effect and nothing anywhere says
     *  why. The response path has carried two such channels since PT-001 and DR-142; the request
     *  path had none, which is how six of DeepSeek's nine accepted block types stayed dropped for a
     *  whole campaign (wall kt-no-println). */
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    /** One line per dropped TYPE for the life of the head, never per block: tools and attachments
     *  re-ride every turn, so an unlatched line would repeat per block per turn. Same idiom as
     *  ToolNameShortener.fullLogged and the translator's two latches. */
    private val droppedLogged = ConcurrentHashMap.newKeySet<String>()

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
        quirks.blockAllowlist?.let { if (type !in it) return dropDisallowed(type) }
        if (isEmptyThinking(type, block)) return null
        return rebuildBlock(block, type)
    }

    /** Drops the block, reporting its type once. An allowlist is only ever as good as the evidence
     *  it was derived from, and the honest way to hold that is to say out loud what it is costing. */
    private fun dropDisallowed(type: String): JsonObject? {
        if (droppedLogged.add(type)) {
            log(
                "[${quirks.providerTag}] content block '$type' is absent from this head's " +
                    "block_allowlist — dropped from the request, so the upstream never sees it\n",
            )
        }
        return null
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
                // V4-32: a replayed tool_use must shorten to the SAME string its declaration
                // did, or the upstream sees a call naming a tool it was never offered.
                key == NAME && type == TYPE_TOOL_USE -> put(NAME, names.shorten(JsonScalars.strOrEmpty(value)))
                else -> put(key, cache.stripCacheControl(value))
            }
        }
    }
}
