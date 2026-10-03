// NEW: V4-456 — semantic event kinds for the raw dialects served by the shared reader.
package splice.head.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.UpstreamGapEnd
import splice.core.util.JsonScalars

// why: Anthropic blocks and chat choices carry their fragments in the same delta field.
private const val UPSTREAM_EVENT_DELTA_FIELD = "delta"

private val CONTENT_STRINGS = listOf("text", "thinking", "arguments", "input", "refusal", "name")
private val CONTENT_NODES = listOf("item", "part", "content_block", "content", "summary", "response", "output")

/** Converts only event shapes and payload presence to telemetry, never provider text. */
internal object UpstreamEventKinds {
    fun kind(event: JsonObject): UpstreamGapEnd {
        val type = JsonScalars.str(event, "type")
        return responsesContent(type) ?: responsesLifecycle(type) ?: anthropic(type, event)
            ?: UpstreamChatEventKinds.kind(event)
    }

    fun hasContent(event: JsonObject, kind: UpstreamGapEnd): Boolean {
        if (event["choices"] is JsonArray) return UpstreamChatEventKinds.hasContent(event)
        return when (kind) {
            UpstreamGapEnd.CONTENT_BLOCK_START, UpstreamGapEnd.CONTENT_BLOCK_STOP,
            UpstreamGapEnd.COMPLETED, UpstreamGapEnd.TORN,
            -> payloadHasContent(event)
            UpstreamGapEnd.THINKING_DELTA, UpstreamGapEnd.TEXT_DELTA,
            UpstreamGapEnd.INPUT_JSON_DELTA, UpstreamGapEnd.TOOL_INPUT_DELTA,
            -> deltaHasContent(event, kind)
            else -> false
        }
    }

    private fun responsesContent(type: String?): UpstreamGapEnd? = when (type) {
        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> UpstreamGapEnd.THINKING_DELTA
        "response.output_text.delta", "response.refusal.delta" -> UpstreamGapEnd.TEXT_DELTA
        "response.function_call_arguments.delta" -> UpstreamGapEnd.INPUT_JSON_DELTA
        "response.custom_tool_call_input.delta" -> UpstreamGapEnd.TOOL_INPUT_DELTA
        "response.output_item.added", "response.content_part.added", "response.reasoning_summary_part.added" ->
            UpstreamGapEnd.CONTENT_BLOCK_START
        else -> null
    }

    private fun responsesLifecycle(type: String?): UpstreamGapEnd? = when (type) {
        "response.created" -> UpstreamGapEnd.MESSAGE_START
        "response.in_progress" -> UpstreamGapEnd.MESSAGE_DELTA
        "response.completed", "response.done", "response.incomplete" -> UpstreamGapEnd.COMPLETED
        "response.failed", "response.error", "error" -> UpstreamGapEnd.TORN
        "response.output_item.done", "response.content_part.done", "response.output_text.done",
        "response.function_call_arguments.done", "response.reasoning_summary_text.done", "response.refusal.done",
        "response.custom_tool_call_input.done", "response.reasoning_text.done", "response.reasoning_summary_part.done",
        -> UpstreamGapEnd.CONTENT_BLOCK_STOP
        else -> null
    }

    private fun anthropic(type: String?, event: JsonObject): UpstreamGapEnd? = when (type) {
        "content_block_delta" -> deltaKind(event[UPSTREAM_EVENT_DELTA_FIELD])
        UpstreamGapEnd.CONTENT_BLOCK_START.wire -> UpstreamGapEnd.CONTENT_BLOCK_START
        UpstreamGapEnd.CONTENT_BLOCK_STOP.wire -> UpstreamGapEnd.CONTENT_BLOCK_STOP
        UpstreamGapEnd.MESSAGE_START.wire -> UpstreamGapEnd.MESSAGE_START
        UpstreamGapEnd.PING.wire -> UpstreamGapEnd.PING
        UpstreamGapEnd.MESSAGE_DELTA.wire -> UpstreamGapEnd.MESSAGE_DELTA
        UpstreamGapEnd.MESSAGE_STOP.wire -> UpstreamGapEnd.MESSAGE_STOP
        else -> null
    }

    private fun deltaKind(element: JsonElement?): UpstreamGapEnd {
        val delta = element as? JsonObject
        return when (JsonScalars.str(delta, "type")) {
            UpstreamGapEnd.THINKING_DELTA.wire -> UpstreamGapEnd.THINKING_DELTA
            UpstreamGapEnd.TEXT_DELTA.wire -> UpstreamGapEnd.TEXT_DELTA
            UpstreamGapEnd.INPUT_JSON_DELTA.wire -> UpstreamGapEnd.INPUT_JSON_DELTA
            else -> UpstreamGapEnd.UNKNOWN
        }
    }

    private fun payloadHasContent(payload: JsonElement?): Boolean = when (payload) {
        is JsonArray -> payload.any(::payloadHasContent)
        is JsonObject -> CONTENT_STRINGS.any { JsonScalars.strIfString(payload[it]).isNotEmpty() } ||
            CONTENT_NODES.any { payloadHasContent(payload[it]) }
        else -> false
    }

    private fun deltaHasContent(event: JsonObject, kind: UpstreamGapEnd): Boolean {
        val delta = event[UPSTREAM_EVENT_DELTA_FIELD]
        return when {
            delta is JsonObject -> {
                val field = when (kind) {
                    UpstreamGapEnd.THINKING_DELTA -> "thinking"
                    UpstreamGapEnd.TEXT_DELTA -> "text"
                    else -> "partial_json"
                }
                JsonScalars.strIfString(delta[field]).isNotEmpty()
            }
            JsonScalars.strIfString(delta).isNotEmpty() -> true
            else -> UpstreamChatEventKinds.hasContent(event)
        }
    }
}
