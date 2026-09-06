// NEW: preserve custom call identity across streamed and terminal Responses carriers.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars

/** One parser for streamed and terminal custom-tool items; interpretation stays outside the dialect. */
internal class ResponsesCustomCallParse {
    fun parse(item: JsonObject): GatewayCustomCall? {
        if (JsonScalars.strOrEmpty(item["type"]) != TYPE_CUSTOM_TOOL_CALL) return null
        return GatewayCustomCall(
            callId = JsonScalars.strOrEmpty(item["call_id"]),
            name = JsonScalars.strOrEmpty(item["name"]),
            input = JsonScalars.strOrEmpty(item["input"]),
            raw = item,
        )
    }

    /** Terminal output owns ordering; streamed-only items survive incomplete terminal carriers. */
    fun merge(streamed: List<GatewayCustomCall>, response: JsonObject?): List<GatewayCustomCall> {
        val terminal = harvest(response)
        val terminalIds = terminal.mapTo(HashSet(), GatewayCustomCall::callId)
        return terminal + streamed.filterNot { it.callId in terminalIds }
    }

    fun harvest(response: JsonObject?): List<GatewayCustomCall> {
        val output = response?.get("output") as? JsonArray ?: return emptyList()
        return output.mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

private const val TYPE_CUSTOM_TOOL_CALL = "custom_tool_call"
