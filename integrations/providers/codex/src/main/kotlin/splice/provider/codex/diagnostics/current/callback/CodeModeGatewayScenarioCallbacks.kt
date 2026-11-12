package splice.provider.codex.diagnostics.current.callback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink

/** Only a catalog-bound, source-observed callback choice can supply the result for a randomized search winner. */
internal class CodeModeGatewayScenarioCallbacks(specification: JsonObject, private val json: Json) {
    private val boundary = JsonScalars.str(specification, "boundary")
    private val expected = ArrayDeque(specification.getValue("callbacks").jsonArray.map { it.jsonObject })
    val count: Int = expected.size
    val accepted = ArrayList<JsonObject>()

    fun accept(sink: CodeModeGatewayBridgeSink) {
        sink.callbackIds.forEachIndexed { index, id ->
            val next = checkNotNull(expected.removeFirstOrNull()) { "Unexpected client callback" }
            val name = sink.callbackNames[index]
            val arguments = json.parseToJsonElement(sink.callbackInputs[index].toString())
            val chosen = choose(next, name, arguments)
            accepted += JsonObject(chosen + ("id" to JsonPrimitive(id)))
        }
    }

    private fun choose(next: JsonObject, name: String, arguments: JsonElement): JsonObject {
        val alternatives = next["alternatives"]
        val choices = if (alternatives == null) {
            listOf(next)
        } else {
            check(boundary == "typed-mcp-delegate" && next.keys == setOf("alternatives")) {
                "Callback alternatives require an explicit typed MCP boundary"
            }
            alternatives.jsonArray.map { it.jsonObject }.also {
                check(it.isNotEmpty() && it.size <= MCP_SEARCH_CALLBACK_CHOICES) { "Unbounded MCP callback choices" }
            }
        }
        return checkNotNull(
            choices.singleOrNull {
                JsonScalars.str(it, "name") == name && it["arguments"] == arguments
            },
        ) { "Client callback has no unique source-observed name and arguments" }
    }

    fun checkComplete() {
        check(expected.isEmpty()) { "Scenario callbacks were not fully consumed" }
    }
}

// why: the real reference MCP search fixture advertises and invokes exactly these three callable branches.
private const val MCP_SEARCH_CALLBACK_CHOICES = 3
