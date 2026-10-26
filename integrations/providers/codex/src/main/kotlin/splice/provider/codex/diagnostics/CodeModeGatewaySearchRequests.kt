package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.JsonWire
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeSettings
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.provider.codex.CodexQuirks
import splice.upstream.BuiltTurn

/** The client catalog and deferred lite declaration are independently carried through the actual turn builder. */
internal class CodeModeGatewaySearchRequests(
    private val fixture: CodeModeGatewayProbeSupport,
    private val settings: CodexCodeModeSettings = CodexCodeModeSettings(),
    namespaceDescription: String = "",
) {
    private val model = "fixture-search-model"
    private val catalog = CodeModeGatewaySearchCatalog(namespaceDescription)
    private val transcript = CodeModeGatewaySearchTranscript(fixture)

    fun prepare(manager: CodexCodeModeBridge, refreshed: Boolean, callbacks: List<JsonObject>): BuiltTurn {
        val tools = catalog.tools(refreshed)
        val body = AnthropicParse.parseAnthropicBody(
            JsonWire.string(
                buildJsonObject {
                    put("model", model); put("max_tokens", 100); put("tools", JsonArray(tools))
                    put("messages", JsonArray(transcript.client(callbacks)))
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
                                catalog.declaration(tools.first()),
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE,
                                        "developer",
                                    ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT, "")
                                },
                            ) + transcript.input(callbacks, builder.toolResults(body).associateBy { it.id }),
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
}

internal const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE = "type"
internal const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION = "description"
internal const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE = "role"
internal const val CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT = "content"
internal const val CALL_NAME = "name"
internal const val CALL_ID = "id"
internal const val CALL_ARGUMENTS = "arguments"
