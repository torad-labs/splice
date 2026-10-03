// NEW: V4-456 — chat choice semantics are separate from Responses and Anthropic event envelopes.
package splice.head.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.UpstreamGapEnd
import splice.core.util.JsonScalars

// The cleartext reasoning keys served by ChatProseFold; values are never retained here.
private val CHAT_REASONING_FIELDS = listOf("reasoning_content", "reasoning", "thinking", "reasoning_text")

// why: chat choices stream their payload and lifecycle fields in the delta object.
private const val CHAT_EVENT_DELTA_FIELD = "delta"

/** Classifies chat choices without confusing empty fields or lifecycle markers with content. */
internal object UpstreamChatEventKinds {
    fun kind(event: JsonObject): UpstreamGapEnd {
        var fallback: UpstreamGapEnd? = null
        for (entry in (event["choices"] as? JsonArray).orEmpty()) {
            val choice = entry as? JsonObject ?: continue
            val kind = deltaKind(choice[CHAT_EVENT_DELTA_FIELD] as? JsonObject)
                ?: deltaKind(choice["message"] as? JsonObject)
            if (kind != null) return kind
            fallback = fallback ?: lifecycle(choice)
        }
        return fallback ?: UpstreamGapEnd.UNKNOWN
    }

    fun hasContent(event: JsonObject): Boolean =
        (event["choices"] as? JsonArray).orEmpty().any { entry ->
            val choice = entry as? JsonObject ?: return@any false
            deltaKind(choice[CHAT_EVENT_DELTA_FIELD] as? JsonObject) != null ||
                deltaKind(choice["message"] as? JsonObject) != null
        }

    private fun deltaKind(delta: JsonObject?): UpstreamGapEnd? {
        val tools = (delta?.get("tool_calls") as? JsonArray).orEmpty()
        return when {
            tools.any { JsonScalars.strIfString(toolFunction(it)?.get("arguments")).isNotEmpty() } ->
                UpstreamGapEnd.INPUT_JSON_DELTA
            tools.any { JsonScalars.strIfString(toolFunction(it)?.get("name")).isNotEmpty() } ->
                UpstreamGapEnd.CONTENT_BLOCK_START
            CHAT_REASONING_FIELDS.any { JsonScalars.strIfString(delta?.get(it)).isNotEmpty() } ->
                UpstreamGapEnd.THINKING_DELTA
            JsonScalars.strIfString(delta?.get("content")).isNotEmpty() -> UpstreamGapEnd.TEXT_DELTA
            else -> null
        }
    }

    private fun lifecycle(choice: JsonObject): UpstreamGapEnd? {
        val delta = choice[CHAT_EVENT_DELTA_FIELD] as? JsonObject
        return when {
            JsonScalars.str(delta, "role") == "assistant" -> UpstreamGapEnd.MESSAGE_START
            (delta?.get("tool_calls") as? JsonArray).orEmpty().any {
                toolFunction(it)?.containsKey("arguments") == true
            } -> UpstreamGapEnd.INPUT_JSON_DELTA
            CHAT_REASONING_FIELDS.any { delta?.containsKey(it) == true } -> UpstreamGapEnd.THINKING_DELTA
            delta?.containsKey("content") == true -> UpstreamGapEnd.TEXT_DELTA
            JsonScalars.str(choice, "finish_reason") != null -> UpstreamGapEnd.MESSAGE_DELTA
            else -> null
        }
    }

    private fun toolFunction(entry: JsonElement): JsonObject? =
        (entry as? JsonObject)?.get("function") as? JsonObject
}
