package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.catalog.CodeModeTurnCatalog
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeToolDefinitions
import splice.upstream.codemode.CodeModeToolSearch

/** Typed MCP clients enter through the existing bridge API after ordinary catalog-checked permission callbacks.
 *  This fixture does not turn a generic Anthropic text result into a raw MCP result. */
internal class CodeModeGatewayMcpRequests(
    private val fixture: CodeModeGatewayProbeSupport,
    private val specification: JsonObject,
) {
    private val model = "fixture-current-mcp-model"
    private val declarations = specification.getValue("declarations").jsonArray
    private val definitions = CodeModeToolDefinitions.collect(declarations)
    private val names = definitions.map { it.codeModeName }.toSet()
    private val replies = CodeModeGatewayMcpResults(names)
    private val ranked = specification["rankedSearch"] == JsonPrimitive(true)
    private val policy = CodeModeToolPolicy(deferredNames = if (ranked) names else emptySet())

    fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn {
        val results = replies.accepted(callbacks)
        val input = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "additional_tools"); put("role", "developer")
                    put("tools", if (ranked) JsonArray(emptyList()) else declarations)
                },
                buildJsonObject { put("role", "developer"); put("content", "") },
                fixture.user("begin-${fixture.nonce}"),
            ) + callbacks.flatMap { callback ->
                val id = checkNotNull(JsonScalars.str(callback, "id"))
                listOf(
                    buildJsonObject {
                        put("type", "function_call"); put("call_id", id)
                        put(
                            "name",
                            callback.getValue("name"),
                        ); put("arguments", JsonWire.string(callback.getValue("arguments")))
                    },
                    buildJsonObject {
                        put("type", "function_call_output"); put("call_id", id)
                        put("output", results.first { it.id == id }.output)
                    },
                )
            },
        )
        val turn = CodexCodeModeBridge.Turn(
            "session-${fixture.nonce}",
            fixture.key,
            model,
            names,
            toolResults = results,
            catalog = CodeModeTurnCatalog(
                definitions + if (ranked) listOf(CodeModeToolSearch.definition()) else emptyList(),
                policy,
            ),
        )
        return BuiltTurn(
            manager.injectTool(buildJsonObject { put("input", input) }, names, policy),
            TurnMeta(
                false,
                reasoning = TurnReasoning(ReasoningDisplay.OFF, "high", null, null),
                route = TurnRoute(true, model, model, 100),
                scope = TurnScope(conversationKey = fixture.key, sessionId = turn.sessionId),
            ),
            roundInterceptor = manager.interceptor(turn, disableParallel = false),
        )
    }

    /** Observe actual accepted gateway values, independently of the callback plan's expected payloads. */
    fun report(manager: CodexCodeModeBridge, report: JsonObject): JsonObject = replies.report(manager, report)
}
