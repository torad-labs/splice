package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.LITE_ADDITIONAL_TOOLS_TYPE

/** Native Codex's first Responses-WebSocket frame: warm only the ordered instruction/tool prefix. */
internal object ResponsesWsPrefill {
    fun request(full: JsonObject): JsonObject? {
        val input = if (isCodeModeRequest(full)) full[FIELD_INPUT] as? JsonArray else null
        val prefix = input?.takeWhile(::isPrefixItem)
        // An empty prefix can still be a valid tool-surface warmup, but an empty frame with no
        // reusable instructions or tools is not useful and must not become a fabricated input.
        return prefix?.takeIf { it.isNotEmpty() && it.any(::isAdditionalTools) }?.let {
            JsonObject(
                full.toMutableMap().apply {
                    this[FIELD_INPUT] = JsonArray(it)
                    this["generate"] = JsonPrimitive(false)
                    remove("previous_response_id")
                },
            )
        }
    }

    fun isCodeModeRequest(request: JsonObject): Boolean {
        if (request["tools"] != null) return false
        val input = request[FIELD_INPUT] as? JsonArray ?: return false
        return input.takeWhile(::isPrefixItem).any(::isAdditionalTools)
    }

    fun prefixItemCount(prefill: JsonObject): Int = (prefill[FIELD_INPUT] as? JsonArray)?.size ?: 0

    /** Require a terminal that proves the server accepted a no-output warmup, not a refusal or
     *  accidental generated answer. The runner commits the response id only after this check. */
    fun responseIdIfCleanTerminal(event: JsonObject): String? {
        val response = event["response"] as? JsonObject
        val output = response?.get("output") as? JsonArray
        return response
            ?.takeIf {
                JsonScalars.str(event["type"]) in CLEAN_TERMINALS &&
                    JsonScalars.str(it["status"]) == "completed" && output?.isEmpty() == true
            }
            ?.let { JsonScalars.str(it["id"])?.takeIf(String::isNotBlank) }
    }

    fun isOutputEvent(event: JsonObject): Boolean {
        val type = JsonScalars.str(event["type"]).orEmpty()
        return type.startsWith("response.") && type !in NON_OUTPUT_EVENT_TYPES
    }

    private fun isPrefixItem(element: kotlinx.serialization.json.JsonElement): Boolean {
        val item = element as? JsonObject ?: return false
        val type = JsonScalars.str(item["type"])
        return JsonScalars.str(item["role"]) in setOf("system", "developer") ||
            type == LITE_ADDITIONAL_TOOLS_TYPE
    }

    private fun isAdditionalTools(element: kotlinx.serialization.json.JsonElement): Boolean =
        JsonScalars.str((element as? JsonObject)?.get("type")) == LITE_ADDITIONAL_TOOLS_TYPE
    private val CLEAN_TERMINALS = setOf("response.completed", "response.done")
    private val NON_OUTPUT_EVENT_TYPES = setOf(
        "response.created",
        "response.in_progress",
        "response.queued",
        "response.completed",
        "response.done",
        "response.metadata",
    )
}
