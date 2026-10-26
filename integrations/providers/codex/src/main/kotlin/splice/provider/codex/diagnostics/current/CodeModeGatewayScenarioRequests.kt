package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.ANTHROPIC_ASSISTANT_ROLE
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.provider.codex.CodexQuirks
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeResult

/** Uses the actual Anthropic result parser and turn builder, with an explicitly bound tool catalog. */
internal class CodeModeGatewayScenarioRequests(
    private val fixture: CodeModeGatewayProbeSupport,
    private val specification: JsonObject,
) {
    private val model = "fixture-current-scenario-model"

    fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn {
        val client = listOf(fixture.user("begin-${fixture.nonce}")) + callbacks.flatMap(::client)
        val body = AnthropicParse.parseAnthropicBody(
            JsonWire.string(
                buildJsonObject {
                    put("model", model); put("max_tokens", 100)
                    put("tools", specification.getValue("tools")); put("messages", JsonArray(client))
                },
            ),
        )
        val builder = CodexCodeModeTurnBuilder(
            manager,
            ResponsesToolResultMedia(CodexQuirks().defaultQuirks()),
            codeModeOnly = CodeModeOnlyModels { listOf(model) },
        )
        val results = builder.toolResults(body).associateBy { it.id }
        return builder.prepare(
            body,
            "session-${fixture.nonce}",
            BuiltTurn(
                buildJsonObject { put("input", input(callbacks, results)) },
                TurnMeta(
                    false,
                    reasoning = TurnReasoning(ReasoningDisplay.OFF, "high", null, null),
                    route = TurnRoute(true, model, model, 100),
                    scope = TurnScope(conversationKey = fixture.key, sessionId = "session-${fixture.nonce}"),
                ),
            ),
        )
    }

    private fun input(callbacks: List<JsonObject>, results: Map<String, CodeModeResult>): JsonArray = JsonArray(
        listOf(
            buildJsonObject {
                put("type", "additional_tools"); put("role", "developer")
                put("tools", specification.getValue("declarations"))
            },
            buildJsonObject { put("role", "developer"); put(SCENARIO_REQUEST_CONTENT, "") },
            fixture.user("begin-${fixture.nonce}"),
        ) + callbacks.flatMap { callback ->
            val id = checkNotNull(JsonScalars.str(callback, "id"))
            listOf(
                buildJsonObject {
                    put("type", "function_call"); put("call_id", id)
                    put(SCENARIO_REQUEST_TOOL_NAME, callback.getValue(SCENARIO_REQUEST_TOOL_NAME))
                    put("arguments", JsonWire.string(callback.getValue("arguments")))
                },
                buildJsonObject {
                    put("type", "function_call_output"); put("call_id", id)
                    put("output", results.getValue(id).output)
                },
            )
        },
    )

    private fun client(callback: JsonObject): List<JsonElement> = listOf(
        buildJsonObject {
            put("role", ANTHROPIC_ASSISTANT_ROLE)
            put(
                SCENARIO_REQUEST_CONTENT,
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_use"); put("id", callback.getValue("id"))
                            put(SCENARIO_REQUEST_TOOL_NAME, callback.getValue(SCENARIO_REQUEST_TOOL_NAME))
                            put("input", callback.getValue("arguments"))
                        },
                    ),
                ),
            )
        },
        buildJsonObject {
            put("role", "user")
            put(
                SCENARIO_REQUEST_CONTENT,
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result"); put("tool_use_id", callback.getValue("id"))
                            put(SCENARIO_REQUEST_CONTENT, callback.getValue("result"))
                            callback["is_error"]?.let { put("is_error", it) }
                        },
                    ),
                ),
            )
        },
    )
}

// why: both the Anthropic callback and Responses history use the same tool-name key.
private const val SCENARIO_REQUEST_TOOL_NAME = "name"

// why: content has the same spelling for both client roles and the Responses developer declaration.
private const val SCENARIO_REQUEST_CONTENT = "content"
