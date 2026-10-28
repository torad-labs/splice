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
import splice.provider.codex.diagnostics.current.catalog.CodeModeGatewayMcpSearch
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeToolDefinitions
import splice.upstream.codemode.CodeModeToolSearch

/** Assemble the real bridge request after a fixture adapter has produced its actual tool results. */
internal class CodeModeGatewayTypedRequests(
    private val fixture: CodeModeGatewayProbeSupport,
    specification: JsonObject,
) {
    private val model = "fixture-current-typed-model"
    private val declarations = specification.getValue("declarations").jsonArray
    val definitions = CodeModeToolDefinitions.collect(declarations)
    private val names = definitions.map { it.codeModeName }.toSet()
    private val ranked = specification["rankedSearch"] == JsonPrimitive(true)
    private val policy = CodeModeToolPolicy(deferredNames = if (ranked) names else emptySet())
    private val mcpSearch = CodeModeGatewayMcpSearch.read(specification)

    fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>, results: List<CodeModeResult>): BuiltTurn {
        check(callbacks.map { JsonScalars.str(it, "id") } == results.map { it.id })
        val turn = CodexCodeModeBridge.Turn(
            "session-${fixture.nonce}",
            fixture.key,
            model,
            names,
            toolResults = results,
            catalog = CodeModeTurnCatalog(
                definitions + if (ranked) listOf(CodeModeToolSearch.definition()) else emptyList(),
                policy,
                mcpSearch = mcpSearch,
            ),
        )
        return BuiltTurn(
            manager.injectTool(buildJsonObject { put("input", input(callbacks, results)) }, names, policy),
            TurnMeta(
                false,
                reasoning = TurnReasoning(ReasoningDisplay.OFF, "high", null, null),
                route = TurnRoute(true, model, model, 100),
                scope = TurnScope(conversationKey = fixture.key, sessionId = turn.sessionId),
            ),
            roundInterceptor = manager.interceptor(turn, disableParallel = false),
        )
    }

    private fun input(callbacks: List<JsonObject>, results: List<CodeModeResult>): JsonArray = JsonArray(
        listOf(
            buildJsonObject {
                put("type", "additional_tools"); put("role", "developer")
                put("tools", if (ranked) JsonArray(emptyList()) else declarations)
            },
            buildJsonObject { put("role", "developer"); put("content", "") },
            fixture.user("begin-${fixture.nonce}"),
        ) + callbacks.zip(results).flatMap { (callback, result) ->
            listOf(
                buildJsonObject {
                    put("type", "function_call"); put("call_id", result.id)
                    put(
                        "name",
                        callback.getValue("name"),
                    ); put("arguments", JsonWire.string(callback.getValue("arguments")))
                },
                buildJsonObject {
                    put("type", "function_call_output"); put("call_id", result.id); put("output", result.output)
                },
            )
        },
    )
}
