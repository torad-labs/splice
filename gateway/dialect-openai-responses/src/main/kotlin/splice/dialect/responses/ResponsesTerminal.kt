// PORT-OF: server/src/codex/translate-response.mjs helpers @ 4ca99f7 — invariants (L4): promote
// only ever promotes MODEL content; "no model text returned" is weak; reasoning summary parts
// join as paragraphs ('\n\n'); harvest reads the terminal Responses object when SSE deltas were
// sparse. ONE implementation for every Responses provider (grok's Node copies were
// byte-identical dupes — the v29 copies-drift lesson).
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.turn.isWeakSummaryText

public data class Harvested(val text: String, val thinking: String)

private fun str(el: kotlinx.serialization.json.JsonElement?): String =
    (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

/** Reasoning item summary parts joined as paragraphs (they arrive as parts; keep readable). */
internal fun summaryText(item: JsonObject): String =
    (item["summary"] as? kotlinx.serialization.json.JsonArray ?: return "")
        .joinToString("\n\n") { part ->
            when (part) {
                is kotlinx.serialization.json.JsonPrimitive -> part.content
                is JsonObject -> str(part["text"])
                else -> ""
            }
        }
        .split("\n\n").filter { it.isNotEmpty() }.joinToString("\n\n")

/** Pull text + thinking from a completed Responses object (when SSE deltas were sparse). */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
// the item/content walk is the literal port
public fun harvestResponsesOutput(resp: JsonObject): Harvested {
    var text = ""
    var thinking = ""
    val output = resp["output"] as? kotlinx.serialization.json.JsonArray ?: return Harvested("", "")
    for (el in output) {
        val item = el as? JsonObject ?: continue
        when (str(item["type"])) {
            "reasoning" -> {
                val t = summaryText(item)
                if (t.isNotEmpty()) thinking += (if (thinking.isNotEmpty()) "\n\n" else "") + t
            }
            "message" -> {
                val content = item["content"] as? kotlinx.serialization.json.JsonArray ?: continue
                for (c in content) {
                    val obj = c as? JsonObject ?: continue
                    val type = str(obj["type"])
                    if (type == "output_text" || type == "text") text += str(obj["text"])
                }
            }
            else -> Unit
        }
    }
    return Harvested(text, thinking)
}

/** Usage extraction: input_tokens|prompt_tokens, output_tokens|completion_tokens. */
public fun usageFrom(resp: JsonObject?): Pair<Long, Long> {
    val usage = resp?.get("usage") as? JsonObject ?: return 0L to 0L
    fun num(vararg keys: String): Long = keys.firstNotNullOfOrNull {
        (usage[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
    } ?: 0L
    return num("input_tokens", "prompt_tokens") to num("output_tokens", "completion_tokens")
}
