// PORT-OF: server/src/codex/translate-response.mjs helpers @ 4ca99f7 — invariants (L4): promote
// only ever promotes MODEL content; "no model text returned" is weak; reasoning summary parts
// join as paragraphs ('\n\n'); harvest reads the terminal Responses object when SSE deltas were
// sparse. ONE implementation for every Responses provider (grok's Node copies were
// byte-identical dupes — the v29 copies-drift lesson).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.Usage

public data class Harvested(val text: String, val thinking: String)

private fun str(el: JsonElement?): String =
    (el as? JsonPrimitive)?.content ?: ""

/** Reasoning item summary parts joined as paragraphs (they arrive as parts; keep readable). */
internal fun summaryText(item: JsonObject): String =
    (item["summary"] as? JsonArray ?: return "")
        .joinToString("\n\n") { part ->
            when (part) {
                is JsonPrimitive -> part.content
                is JsonObject -> str(part["text"])
                else -> ""
            }
        }
        .split("\n\n").filter { it.isNotEmpty() }.joinToString("\n\n")

/** Pull text + thinking from a completed Responses object (when SSE deltas were sparse). */
// the item/content walk is the literal port
public fun harvestResponsesOutput(resp: JsonObject): Harvested {
    val output = resp["output"] as? JsonArray ?: return Harvested("", "")
    val text = StringBuilder()
    val thinking = StringBuilder()
    for (el in output) {
        val item = el as? JsonObject ?: continue
        when (str(item["type"])) {
            "reasoning" -> appendReasoningSummary(thinking, item)
            "message" -> text.append(messageText(item))
            else -> Unit
        }
    }
    return Harvested(text.toString(), thinking.toString())
}

/** Append one reasoning item's summary as a blank-line-separated paragraph (only when present). */
private fun appendReasoningSummary(thinking: StringBuilder, item: JsonObject) {
    val t = summaryText(item)
    if (t.isEmpty()) return
    if (thinking.isNotEmpty()) thinking.append("\n\n")
    thinking.append(t)
}

/** Concatenate the output_text/text parts of one message item, in order. */
private fun messageText(item: JsonObject): String {
    val content = item["content"] as? JsonArray ?: return ""
    val out = StringBuilder()
    for (c in content) {
        val obj = c as? JsonObject ?: continue
        val type = str(obj["type"])
        if (type == "output_text" || type == "text") out.append(str(obj["text"]))
    }
    return out.toString()
}

/** Usage extraction: input/output plus the prompt-cache read (input_tokens_details.cached_tokens,
 *  with the flat cache_read_input_tokens as the fallback) — so the real cache hit rate is visible. */
public fun usageFrom(resp: JsonObject?): Usage {
    val usage = resp?.get("usage") as? JsonObject ?: return Usage()
    fun num(obj: JsonObject, vararg keys: String): Long = keys.firstNotNullOfOrNull {
        (obj[it] as? JsonPrimitive)?.content?.toLongOrNull()
    } ?: 0L
    val details = usage["input_tokens_details"] as? JsonObject
    val cached = details?.let { num(it, "cached_tokens") }?.takeIf { it > 0 }
        ?: num(usage, "cache_read_input_tokens")
    return Usage(
        inputTokens = num(usage, "input_tokens", "prompt_tokens"),
        outputTokens = num(usage, "output_tokens", "completion_tokens"),
        cachedTokens = cached,
    )
}
