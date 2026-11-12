package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.callbacks.mcp.CodeModeMcpRpcErrors
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import splice.upstream.codemode.CodeModeToolOutputs

/** Validate the typed fixture receipt and observe what the production bridge actually retained. */
internal class CodeModeGatewayMcpResults(private val names: Set<String>) {
    fun accepted(callbacks: List<JsonObject>): List<CodeModeResult> = callbacks.map(::result)

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
