package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import splice.provider.codex.callbacks.mcp.CodeModeMcpRpcErrors
import splice.provider.codex.catalog.CodeModeTurnCatalog
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import splice.upstream.codemode.CodeModeToolDefinitions
import splice.upstream.codemode.CodeModeToolOutputs
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
    private val ranked = specification["rankedSearch"] == JsonPrimitive(true)
    private val policy = CodeModeToolPolicy(deferredNames = if (ranked) names else emptySet())

    fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn {
        val results = callbacks.map(::result)
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

    private fun result(callback: JsonObject): CodeModeResult {
        check(JsonScalars.str(callback, "name") in names) { "MCP result has no advertised permission tool" }
        val value = value(callback)
        check(value["content"] is JsonArray) { "MCP result omitted CallToolResult.content" }
        return CodeModeResult(
            checkNotNull(JsonScalars.str(callback, "id")),
            JsonWire.string(value),
            value["isError"] == JsonPrimitive(true),
            payload = value,
            disposition = CodeModeResultDisposition.TOOL_OUTPUT,
            origin = CodeModeResultOrigin.MCP,
        )
    }

    private fun value(callback: JsonObject): JsonObject {
        if (JsonScalars.str(callback, "origin") == "MCP") {
            check(callback["rpcError"] == null) { "A successful MCP result cannot also claim an RPC failure" }
            return callback.getValue("result").jsonObject
        }
        check(JsonScalars.str(callback, "origin") == "MCP_RPC_ERROR") { "MCP callback has no typed origin" }
        check(callback["result"] == null) { "An RPC failure cannot also claim a successful MCP result" }
        val server = checkNotNull(JsonScalars.str(callback, "server"))
        val tool = checkNotNull(JsonScalars.str(callback, "tool"))
        check(server == "parity" && JsonScalars.str(callback, "name") == "mcp__${server}__$tool")
        return checkNotNull(CodeModeMcpRpcErrors.result(server, tool, callback.getValue("rpcError").jsonObject))
    }

    /** Observe actual accepted gateway values, independently of the callback plan's expected payloads. */
    fun report(manager: CodexCodeModeBridge, report: JsonObject): JsonObject = JsonObject(
        report + mapOf("boundary" to JsonPrimitive("typed-mcp-delegate"), "toolResults" to retained(manager)),
    )

    private fun retained(manager: CodexCodeModeBridge): JsonArray = JsonArray(
        manager.registry.records.toList().flatMap { record ->
            record.results.values.filter { it.origin == CodeModeResultOrigin.MCP }.map { result ->
                buildJsonObject {
                    put("id", result.id); put("origin", result.origin.name)
                    put("disposition", result.disposition.name); put("isError", result.isError)
                    put("payload", checkNotNull(result.payload)); put("value", CodeModeToolOutputs.value(result))
                }
            }
        },
    )
}
