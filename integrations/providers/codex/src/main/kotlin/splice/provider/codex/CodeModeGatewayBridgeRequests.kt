package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.JsonWire
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.upstream.BuiltTurn

internal class CodeModeGatewayBridgeRequests(private val fixture: CodeModeGatewayProbeSupport) {
    private val model = "fixture-model"
    fun prepare(
        manager: CodexCodeModeBridge,
        id: String? = null,
        result: String = "",
        tail: String? = null,
        prefix: CodeModeGatewayPrefix = CodeModeGatewayPrefix.ORIGINAL,
    ): BuiltTurn {
        val initial = initial(prefix)
        val messages = initial.toMutableList()
        if (id != null) messages += callbacks(id, result)
        tail?.let { messages += fixture.user(it) }
        val body = AnthropicParse.parseAnthropicBody(
            buildJsonObject {
                put("model", model); put("max_tokens", 100)
                put("tools", fixture.json.parseToJsonElement("""[{"name":"Read","input_schema":{"type":"object"}}]"""))
                put("messages", JsonArray(messages))
            }.let(JsonWire::string),
        )
        val builder = CodexCodeModeTurnBuilder(
            manager,
            ResponsesToolResultMedia(CodexQuirks().defaultQuirks()),
            codeModeOnly = CodeModeOnlyModels { listOf(model) },
        )
        val built = built(prefix)
        val input = built.requestBody.getValue("input").jsonArray.map { it.jsonObject }.toMutableList()
        input += initial
        id?.let { input += fixture.callback(it).first().jsonObject }
        builder.toolResults(body).forEach { value ->
            input += buildJsonObject {
                put("type", "function_call_output"); put("call_id", value.id); put("output", value.output)
            }
        }
        tail?.let { input += fixture.user(it) }
        return builder.prepare(
            body,
            "session-${fixture.nonce}",
            built.copy(requestBody = buildJsonObject { put("input", JsonArray(input)) }),
        )
    }

    private fun callbacks(id: String, result: String): List<JsonObject> = listOf(
        fixture.json.parseToJsonElement(
            """{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"$id","name":"Read","input":{}}]}""",
        ).jsonObject,
        buildJsonObject {
            put("role", "user")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result"); put("tool_use_id", id)
                            put(
                                "content",
                                JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", result) })),
                            )
                        },
                    ),
                ),
            )
        },
    )

    private fun built(prefix: CodeModeGatewayPrefix): BuiltTurn = BuiltTurn(
        fixture.json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer",""" +
                """"tools":[{"type":"function","name":"Read"}]},""" +
                """{"role":"developer","content":""}]}""",
        ).jsonObject,
        TurnMeta(
            prefix == CodeModeGatewayPrefix.COMPACTED,
            reasoning = TurnReasoning(ReasoningDisplay.OFF, "high", null, null),
            route = TurnRoute(true, model, model, 100),
            scope = TurnScope(conversationKey = fixture.key, sessionId = "session-${fixture.nonce}"),
        ),
    )

    private fun initial(prefix: CodeModeGatewayPrefix): List<JsonObject> = when (prefix) {
        CodeModeGatewayPrefix.ORIGINAL -> listOf(
            fixture.user("start-${fixture.nonce}"),
            fixture.user("branch-${fixture.nonce}"),
        )
        CodeModeGatewayPrefix.REWOUND -> listOf(fixture.user("start-${fixture.nonce}"))
        CodeModeGatewayPrefix.COMPACTED -> listOf(
            fixture.user("You have been tasked with summarizing conversations. Summary-${fixture.nonce}"),
        )
    }
}

internal enum class CodeModeGatewayPrefix { ORIGINAL, REWOUND, COMPACTED }
