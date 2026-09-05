// PORT-OF: server/src/codex/translate-response.mjs helpers @ pre-public-port-baseline — invariants (L4): promote
// only ever promotes MODEL content; "no model text returned" is weak; reasoning summary parts
// join as paragraphs ('\n\n'); harvest reads the terminal Responses object when SSE deltas were
// sparse. ONE implementation for every Responses provider (grok's Node copies were
// byte-identical dupes — the v29 copies-drift lesson).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.ToolSearchCall
import splice.core.turn.Usage
import splice.core.util.JsonScalars

// Harvested lives in Harvested.kt (concentration, 2026-08-19).

/**
 * The terminal-object readers. Every member keeps the name and argument list it had as a file-level
 * function (Kotlin main sources carry no top-level functions), so a call site only gained a
 * receiver.
 */
internal class ResponsesHarvest {

    // The ONE tool_search_call parser (ResponsesToolSearchParse.kt) — never a second hand-rolled
    // reader of the same shape (the v29 copies-drift law).
    private val frames = ResponsesToolSearchParse()

    /** Drop empty segments so joined parts render as clean blank-line paragraphs. */
    private fun normalizeParagraphs(joined: String): String =
        joined.split(PARA).filter { it.isNotEmpty() }.joinToString(PARA)

    /** Reasoning item summary parts joined as paragraphs (they arrive as parts; keep readable). */
    internal fun summaryText(item: JsonObject): String =
        normalizeParagraphs(
            (item["summary"] as? JsonArray ?: return "")
                .joinToString(PARA) { part ->
                    when (part) {
                        is JsonPrimitive -> JsonScalars.strOrEmpty(part)
                        is JsonObject -> JsonScalars.strOrEmpty(part[FIELD_TEXT])
                        else -> ""
                    }
                },
        )

    /**
     * Fullest human-readable reasoning text from a completed reasoning item.
     * Prefers free-form content fields some backends put on the item (`content` / `text` /
     * `reasoning` / `reasoning_content`) and falls back to the structured `summary` parts.
     * Encrypted blobs are never decoded here — they are opaque and only useful for replay.
     */
    internal fun reasoningReadableText(item: JsonObject): String =
        FULL_REASONING_KEYS.firstNotNullOfOrNull { key -> freeFormReasoningText(item[key]) }
            ?: summaryText(item)

    /** One free-form reasoning field rendered readable, or null when absent/empty. */
    private fun freeFormReasoningText(v: JsonElement?): String? = when (v) {
        is JsonPrimitive -> JsonScalars.strOrEmpty(v).ifEmpty { null }
        is JsonArray -> normalizeParagraphs(
            v.joinToString(PARA) { part ->
                when (part) {
                    is JsonPrimitive -> JsonScalars.strOrEmpty(part)
                    is JsonObject ->
                        JsonScalars.strOrEmpty(part[FIELD_TEXT])
                            .ifEmpty { JsonScalars.strOrEmpty(part[FIELD_CONTENT]) }
                    else -> ""
                }
            },
        ).ifEmpty { null }
        is JsonObject ->
            JsonScalars.strOrEmpty(v[FIELD_TEXT])
                .ifEmpty { JsonScalars.strOrEmpty(v[FIELD_CONTENT]) }
                .ifEmpty { null }
        else -> null
    }

    /** Pull text + thinking from a completed Responses object (when SSE deltas were sparse). */
    // the item/content walk is the literal port
    public fun harvestResponsesOutput(resp: JsonObject): Harvested {
        val output = resp["output"] as? JsonArray ?: return Harvested("", "")
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (el in output) {
            val item = el as? JsonObject ?: continue
            when (JsonScalars.strOrEmpty(item["type"])) {
                "reasoning" -> appendReasoningText(thinking, item)
                "message" -> text.append(messageText(item))
                else -> Unit
            }
        }
        return Harvested(text.toString(), thinking.toString())
    }

    /** One line naming the completed response's status and every output item by type, with the
     *  sizes that decide whether it could reach the client: a message's part types and text length,
     *  a reasoning item's summary count and encrypted length, any other type by its key set. This
     *  is the evidence the empty-turn honesty gate prints — an item type this dialect does not
     *  render (agent_message, custom_tool_call, context_compaction, ...) is invisible everywhere
     *  else, and "model returned no content" was asserting an absence nobody could grep. */
    public fun describeOutput(resp: JsonObject?, streamedTypes: List<String> = emptyList()): String {
        if (resp == null) {
            val streamed = if (streamedTypes.isEmpty()) "" else " streamed=[${streamedTypes.joinToString(",")}]"
            return "status=<no terminal response>$streamed"
        }
        val status = JsonScalars.strOrEmpty(resp["status"]).ifEmpty { "?" }
        val output = resp["output"] as? JsonArray ?: return "status=$status items=<none>"
        val items = output.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val type = JsonScalars.strOrEmpty(item["type"]).ifEmpty { "?" }
            when (type) {
                "message" -> {
                    val parts = (item["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                    val detail = parts.joinToString(",") { part ->
                        val t = JsonScalars.strOrEmpty(part["type"]).ifEmpty { "?" }
                        val len = JsonScalars.strOrEmpty(part["text"]).length + JsonScalars.strOrEmpty(part["refusal"]).length
                        "$t:$len"
                    }
                    "message(${detail.ifEmpty { "no parts" }})"
                }
                "reasoning" -> {
                    val summaries = (item["summary"] as? JsonArray)?.size ?: 0
                    val enc = JsonScalars.strOrEmpty(item["encrypted_content"]).length
                    "reasoning(summary=$summaries,enc=$enc)"
                }
                "function_call" -> "function_call(${JsonScalars.strOrEmpty(item["name"])})"
                else -> "$type(${item.keys.filter { it != "type" }.joinToString(",")})"
            }
        }
        val streamed = if (streamedTypes.isEmpty()) "" else " streamed=[${streamedTypes.joinToString(",")}]"
        return "status=$status items=[${items.joinToString(" ")}]$streamed"
    }

    /** Append one reasoning item's fullest readable text as a blank-line-separated paragraph. */
    private fun appendReasoningText(thinking: StringBuilder, item: JsonObject) {
        val t = reasoningReadableText(item)
        if (t.isEmpty()) return
        if (thinking.isNotEmpty()) thinking.append(PARA)
        thinking.append(t)
    }

    /** Concatenate the output_text/text parts of one message item, in order. */
    private fun messageText(item: JsonObject): String {
        val content = item["content"] as? JsonArray ?: return ""
        val out = StringBuilder()
        for (c in content) {
            val obj = c as? JsonObject ?: continue
            val type = JsonScalars.strOrEmpty(obj["type"])
            if (type == "output_text" || type == FIELD_TEXT) out.append(JsonScalars.strOrEmpty(obj[FIELD_TEXT]))
        }
        return out.toString()
    }

    /** tool_search_call items on the completed response object — the same walk [harvestResponsesOutput]
     *  performs, filtered to tool_search_call, reusing [ResponsesFrameParse.parseToolSearchCall]
     *  (ResponsesStreamTranslator.kt) so there is ONE parser, not two (the v29 copies-drift law).
     *  Recovers a round the live output_item.done capture missed (delivered ONLY on the terminal
     *  object, no per-item events). */
    internal fun harvestToolSearchCalls(resp: JsonObject?): List<ToolSearchCall> {
        val output = resp?.get("output") as? JsonArray ?: return emptyList()
        return output.asSequence()
            .filterIsInstance<JsonObject>()
            .filter { JsonScalars.strOrEmpty(it["type"]) == TYPE_TOOL_SEARCH_CALL }
            .mapNotNull(frames::parseToolSearchCall)
            .toList()
    }

    /** Usage extraction: input/output plus the prompt-cache read (input_tokens_details.cached_tokens,
     *  with the flat cache_read_input_tokens as the fallback) — so the real cache hit rate is visible. */
    public fun usageFrom(resp: JsonObject?): Usage {
        val usage = resp?.get("usage") as? JsonObject ?: return Usage()
        val details = usage["input_tokens_details"] as? JsonObject
        val cached = JsonScalars.firstLong(details, "cached_tokens")?.takeIf { it > 0 }
            ?: JsonScalars.firstLong(usage, "cache_read_input_tokens") ?: 0L
        // output_tokens_details.reasoning_tokens carries the 518n-2 truncation fingerprint; absent on
        // non-reasoning backends (→ 0 → never fold).
        val reasoning =
            JsonScalars.firstLong(usage["output_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0L
        return Usage(
            inputTokens = JsonScalars.firstLong(usage, "input_tokens", "prompt_tokens") ?: 0L,
            outputTokens = JsonScalars.firstLong(usage, "output_tokens", "completion_tokens") ?: 0L,
            cachedTokens = cached,
            reasoningTokens = reasoning,
        )
    }
}

private const val PARA = "\n\n"
private const val FIELD_TEXT = "text"
private const val FIELD_CONTENT = "content"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"

// FILE SCOPE ON PURPOSE: one shared key list; as a member it would be rebuilt per instance.
private val FULL_REASONING_KEYS = listOf(FIELD_CONTENT, FIELD_TEXT, "reasoning", "reasoning_content")
