// NEW: preserve custom call identity across streamed and terminal Responses carriers.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars

/** One parser for streamed and terminal custom-tool items; interpretation stays outside the dialect. */
internal class ResponsesCustomCallParse(private val codeMode: Boolean = false) {
    fun parse(item: JsonObject): GatewayCustomCall? {
        val wait = isWait(item)
        if (JsonScalars.strOrEmpty(item["type"]) != TYPE_CUSTOM_TOOL_CALL && !wait) return null
        return GatewayCustomCall(
            callId = JsonScalars.strOrEmpty(item["call_id"]),
            name = JsonScalars.strOrEmpty(item["name"]),
            input = JsonScalars.strOrEmpty(item[if (wait) "arguments" else "input"]),
            raw = item,
        )
    }

    fun isWait(item: JsonObject): Boolean = codeMode && JsonScalars.str(item, "type") == "function_call" &&
        JsonScalars.str(item, "name") == "wait"

    /** Completed items keep their arrival order and source; terminal-only fallbacks append. */
    fun merge(streamed: List<GatewayCustomCall>, response: JsonObject?): List<GatewayCustomCall> {
        val terminal = harvest(response)
        val completed = streamed.distinct()
        val streamedIds = completed.mapTo(HashSet(), GatewayCustomCall::callId)
        return completed + terminal.filterNot { it.callId in streamedIds }
    }

    fun harvest(response: JsonObject?): List<GatewayCustomCall> {
        val output = response?.get("output") as? JsonArray ?: return emptyList()
        return output.mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

private const val TYPE_CUSTOM_TOOL_CALL = "custom_tool_call"
