package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.diagnostics.CodeModeGatewayNativeResults

/** Each scripted call and client response is consumed once; unexpected callbacks fail the probe. */
internal class CodeModeGatewayScenarioPlan(specification: JsonObject, private val json: Json) {
    private val actions = ArrayDeque(specification.getValue("actions").jsonArray.map { it.jsonObject })
    private val expectedCallbacks = ArrayDeque(specification.getValue("callbacks").jsonArray.map { it.jsonObject })
    private var lastExec: String? = null
    val callbacks = ArrayList<JsonObject>()
    var finished: Boolean = false
        private set
    val stageLimit: Int = actions.size + expectedCallbacks.size + 1

    init {
        require(specification["schema"] == JsonPrimitive(1)) { "Unsupported scenario schema" }
    }

    fun next(input: JsonArray): List<GatewayCustomCall> {
        val action = actions.removeFirstOrNull() ?: return emptyList<GatewayCustomCall>().also { finished = true }
        val calls = action["calls"]?.jsonArray?.map { it.jsonObject } ?: listOf(action)
        require(calls.isNotEmpty()) { "Scenario contains an empty outer-call batch" }
        return calls.map { call(it, input) }
    }

    private fun call(action: JsonObject, input: JsonArray): GatewayCustomCall {
        val name = checkNotNull(JsonScalars.str(action, "name"))
        val id = checkNotNull(JsonScalars.str(action, "call_id"))
        require(name == "exec" || name == "wait") { "Scenario contains an unsupported outer call" }
        val value = if (name == "exec") {
            lastExec = id
            checkNotNull(JsonScalars.str(action, "input"))
        } else {
            val arguments = action.getValue("arguments").jsonObject
            JsonWire.string(
                if (JsonScalars.str(arguments, "cell_id") == "last_yielded_cell") {
                    JsonObject(
                        arguments + (
                            "cell_id" to JsonPrimitive(
                                CodeModeGatewayNativeResults.cell(input, checkNotNull(lastExec)),
                            )
                            ),
                    )
                } else {
                    arguments
                },
            )
        }
        val outer = JsonObject(action + ((if (name == "exec") "input" else "arguments") to JsonPrimitive(value)))
        return GatewayCustomCall(id, name, value, outer)
    }

    fun accept(sink: CodeModeGatewayBridgeSink) {
        sink.callbackIds.forEachIndexed { index, id ->
            val expected = checkNotNull(expectedCallbacks.removeFirstOrNull()) { "Unexpected client callback" }
            val name = sink.callbackNames[index]
            val arguments = json.parseToJsonElement(sink.callbackInputs[index].toString())
            check(JsonScalars.str(expected, "name") == name) { "Client callback name differs from bound input" }
            check(expected.getValue("arguments") == arguments) { "Client callback arguments differ from bound input" }
            callbacks += JsonObject(expected + ("id" to JsonPrimitive(id)))
        }
    }

    fun checkComplete() {
        check(finished && actions.isEmpty() && expectedCallbacks.isEmpty()) { "Scenario was not fully consumed" }
    }
}
