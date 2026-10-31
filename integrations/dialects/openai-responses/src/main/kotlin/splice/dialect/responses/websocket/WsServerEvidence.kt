// NEW: V4-446 — retain only response facts the WebSocket server actually emitted.
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** The committed backend response, not an inference from the client's rebuilt history. */
internal data class WsServerEvidence(
    val assistantTexts: List<String> = emptyList(),
    val calls: Map<String, JsonObject> = emptyMap(),
    /** The replay codec preserves id and encrypted_content, not the output item's JSON shape. */
    val reasoning: Map<String, String> = emptyMap(),
    /** Only reasoning before a call is rebuilt from the tool-id cache. */
    val requiredReasoning: List<String> = if (calls.isEmpty()) emptyList() else reasoning.keys.toList(),
) {
    fun verifies(item: JsonObject): Boolean = when (JsonScalars.str(item[WS_FIELD_TYPE])) {
        REASONING_ITEM_TYPE -> matchesReasoning(item)
        "function_call", "custom_tool_call" -> matchesCall(item)
        else -> false
    }

    private fun matchesReasoning(item: JsonObject): Boolean {
        val id = JsonScalars.str(item["id"])
        val cipher = JsonScalars.str(item["encrypted_content"])
        return id != null && cipher != null && reasoning[id] == cipher
    }

    fun trackObserved(item: JsonObject, callsSeen: MutableList<String>, reasoningSeen: MutableList<String>) {
        if (JsonScalars.str(item[WS_FIELD_TYPE]) == REASONING_ITEM_TYPE) {
            reasoningSeen += JsonScalars.str(item["id"]).orEmpty()
        } else {
            callsSeen += JsonScalars.str(item[FIELD_CALL_ID]).orEmpty()
        }
    }

    fun completeEcho(assistantCount: Int, echoedCalls: List<String>, echoedReasoning: List<String>): Boolean =
        assistantCount == assistantTexts.size &&
            echoedCalls == calls.keys.toList() &&
            echoedReasoning.filter(requiredReasoning::contains) == requiredReasoning

    fun matchesCall(item: JsonObject): Boolean {
        val echoedId = JsonScalars.str(item[FIELD_CALL_ID]) ?: return false
        val observed = calls[echoedId] ?: return false
        val required = requiredCallFields(item)
        // An empty server call_id reaches the client under the function_call item's id.
        val fallback = JsonScalars.str(observed[FIELD_CALL_ID]).isNullOrEmpty() &&
            JsonScalars.str(observed["id"]) == echoedId
        return required != null && item.keys.containsAll(required) && item.all { (field, value) ->
            if (field == FIELD_CALL_ID && fallback) {
                JsonScalars.str(value) == echoedId
            } else {
                observed[field] == value
            }
        }
    }

    private fun requiredCallFields(item: JsonObject): Set<String>? = when (JsonScalars.str(item[WS_FIELD_TYPE])) {
        "function_call" -> setOf(WS_FIELD_TYPE, FIELD_CALL_ID, "name", "arguments")
        "custom_tool_call" -> setOf(WS_FIELD_TYPE, FIELD_CALL_ID, "name", "input")
        else -> null
    }
}
