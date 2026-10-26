package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeSettings
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.provider.codex.CodexQuirks
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeToolSearch

/** The client catalog and deferred lite declaration are independently carried through the actual turn builder. */
internal class CodeModeGatewaySearchRequests(
    private val fixture: CodeModeGatewayProbeSupport,
    private val settings: CodexCodeModeSettings = CodexCodeModeSettings(),
    private val namespaceDescription: String = "",
) {
    private val model = "fixture-search-model"
    private val schema = buildJsonObject {
        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE, "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE, "string")
                        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION, "Lookup value.")
                    },
                )
            },
        )
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("value")) })
    }

    fun prepare(manager: CodexCodeModeBridge, refreshed: Boolean, callbacks: List<JsonObject>): BuiltTurn {
        val tools = tools(refreshed)
        val body = AnthropicParse.parseAnthropicBody(
            JsonWire.string(
                buildJsonObject {
                    put("model", model); put("max_tokens", 100); put("tools", JsonArray(tools))
                    put("messages", JsonArray(client(callbacks)))
                },
            ),
        )
        val builder = CodexCodeModeTurnBuilder(
            manager,
            ResponsesToolResultMedia(CodexQuirks().defaultQuirks()),
            codeModeOnly = CodeModeOnlyModels { listOf(model) },
            settings = settings,
        )
        return builder.prepare(
            body,
            "session-${fixture.nonce}",
            BuiltTurn(
                buildJsonObject {
                    put(
                        "input",
                        JsonArray(
                            listOf(
                                declaration(tools.first()),
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE,
                                        "developer",
                                    ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT, "")
                                },
                            ) + input(callbacks, builder.toolResults(body).associateBy { it.id }),
                        ),
                    )
                },
                TurnMeta(
                    false,
                    reasoning = TurnReasoning(ReasoningDisplay.OFF, "high", null, null),
                    route = TurnRoute(true, model, model, 100),
                    scope = TurnScope(conversationKey = fixture.key, sessionId = "session-${fixture.nonce}"),
                ),
            ),
        )
    }

    private fun tools(refreshed: Boolean): List<JsonObject> {
        return listOf(
            tool("Ping", "Health check"),
            tool(
                if (refreshed) "fresh.tool" else "hidden-tool",
                if (refreshed) "Quasar lookup version two" else "Quasar lookup version one",
            ),
        )
    }

    private fun tool(name: String, description: String): JsonObject = buildJsonObject {
        put(
            CALL_NAME,
            name,
        ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION, description); put("input_schema", schema)
    }

    private fun declaration(eager: JsonObject): JsonObject = buildJsonObject {
        put(
            CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
            "additional_tools",
        ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, "developer")
        val members = listOf(
            buildJsonObject {
                put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                    "function",
                ); put(
                    CALL_NAME,
                    eager.getValue(CALL_NAME),
                ); put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION,
                    eager.getValue(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION),
                )
                put("parameters", schema)
            },
            buildJsonObject {
                put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                    "tool_search",
                ); put("parameters", CodeModeToolSearch.inputSchema)
            },
        )
        val grouped = ResponsesFunctionNamespace().group(JsonArray(members)).map { member ->
            val item = member as JsonObject
            if (JsonScalars.str(item, CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE) == "namespace") {
                JsonObject(
                    item + (
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION to kotlinx.serialization.json.JsonPrimitive(
                            namespaceDescription,
                        )
                        ),
                )
            } else {
                item
            }
        }
        put("tools", JsonArray(grouped))
    }

    private fun client(callbacks: List<JsonObject>): List<JsonElement> = listOf(
        fixture.user("begin-${fixture.nonce}"),
    ) +
        callbacks.flatMap { callback ->
            listOf(
                buildJsonObject {
                    put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, ANTHROPIC_ASSISTANT_ROLE); put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT,
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                                        "tool_use",
                                    ); put(
                                        CALL_ID,
                                        callback.getValue(CALL_ID),
                                    ); put(
                                        CALL_NAME,
                                        callback.getValue(CALL_NAME),
                                    )
                                    put("input", callback.getValue(CALL_ARGUMENTS))
                                },
                            )
                        },
                    )
                },
                buildJsonObject {
                    put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, "user"); put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT,
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                                        "tool_result",
                                    ); put(
                                        "tool_use_id",
                                        callback.getValue(CALL_ID),
                                    ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT, callback.getValue("result"))
                                    callback["is_error"]?.let { put("is_error", it) }
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun input(callbacks: List<JsonObject>, results: Map<String, CodeModeResult>): List<JsonElement> = listOf(
        fixture.user("begin-${fixture.nonce}"),
    ) +
        callbacks.flatMap { callback ->
            listOf(
                buildJsonObject {
                    put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                        "function_call",
                    ); put(
                        "call_id",
                        callback.getValue(CALL_ID),
                    ); put(
                        CALL_NAME,
                        callback.getValue(CALL_NAME),
                    )
                    put(
                        CALL_ARGUMENTS,
                        JsonWire.string(callback.getValue(CALL_ARGUMENTS)),
                    )
                },
                buildJsonObject {
                    put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                        "function_call_output",
                    ); put("call_id", callback.getValue(CALL_ID))
                    put(
                        "output",
                        results.getValue(
                            checkNotNull(JsonScalars.str(callback, CALL_ID)),
                        ).output,
                    )
                },
            )
        }
}

private const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE = "type"
private const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION = "description"
private const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE = "role"
private const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT = "content"
private const val CALL_NAME = "name"
private const val CALL_ID = "id"
private const val CALL_ARGUMENTS = "arguments"
