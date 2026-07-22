// PORT-OF: server/src/codex/translate-response.mjs helpers @ pre-public-port-baseline — invariants (L4): promote
// only ever promotes MODEL content; "no model text returned" is weak; reasoning summary parts
// join as paragraphs ('\n\n'); harvest reads the terminal Responses object when SSE deltas were
// sparse. ONE implementation for every Responses provider (grok's Node copies were
// byte-identical dupes — the v29 copies-drift lesson).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.Usage

public data class Harvested(val text: String, val thinking: String)

// JsonNull-safe (same trap as ResponsesStreamTranslator.str): null text fields must not harvest "null".
private fun str(el: JsonElement?): String =
    (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""

private const val PARA = "\n\n"
private const val FIELD_TEXT = "text"
private const val FIELD_CONTENT = "content"

/** Drop empty segments so joined parts render as clean blank-line paragraphs. */
private fun normalizeParagraphs(joined: String): String =
    joined.split(PARA).filter { it.isNotEmpty() }.joinToString(PARA)

/** Reasoning item summary parts joined as paragraphs (they arrive as parts; keep readable). */
internal fun summaryText(item: JsonObject): String =
    normalizeParagraphs(
        (item["summary"] as? JsonArray ?: return "")
            .joinToString(PARA) { part ->
                when (part) {
                    is JsonPrimitive -> str(part)
                    is JsonObject -> str(part[FIELD_TEXT])
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
    is JsonPrimitive -> str(v).ifEmpty { null }
    is JsonArray -> normalizeParagraphs(
        v.joinToString(PARA) { part ->
            when (part) {
                is JsonPrimitive -> str(part)
                is JsonObject -> str(part[FIELD_TEXT]).ifEmpty { str(part[FIELD_CONTENT]) }
                else -> ""
            }
        },
    ).ifEmpty { null }
    is JsonObject -> str(v[FIELD_TEXT]).ifEmpty { str(v[FIELD_CONTENT]) }.ifEmpty { null }
    else -> null
}

private val FULL_REASONING_KEYS = listOf(FIELD_CONTENT, FIELD_TEXT, "reasoning", "reasoning_content")

/** Pull text + thinking from a completed Responses object (when SSE deltas were sparse). */
// the item/content walk is the literal port
public fun harvestResponsesOutput(resp: JsonObject): Harvested {
    val output = resp["output"] as? JsonArray ?: return Harvested("", "")
    val text = StringBuilder()
    val thinking = StringBuilder()
    for (el in output) {
        val item = el as? JsonObject ?: continue
        when (str(item["type"])) {
            "reasoning" -> appendReasoningText(thinking, item)
            "message" -> text.append(messageText(item))
            else -> Unit
        }
    }
    return Harvested(text.toString(), thinking.toString())
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
        val type = str(obj["type"])
        if (type == "output_text" || type == FIELD_TEXT) out.append(str(obj[FIELD_TEXT]))
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
    // output_tokens_details.reasoning_tokens carries the 518n-2 truncation fingerprint; absent on
    // non-reasoning backends (→ 0 → never fold).
    val reasoning = (usage["output_tokens_details"] as? JsonObject)?.let { num(it, "reasoning_tokens") } ?: 0L
    return Usage(
        inputTokens = num(usage, "input_tokens", "prompt_tokens"),
        outputTokens = num(usage, "output_tokens", "completion_tokens"),
        cachedTokens = cached,
        reasoningTokens = reasoning,
    )
}
