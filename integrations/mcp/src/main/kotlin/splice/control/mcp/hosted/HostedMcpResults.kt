package splice.control.mcp.hosted

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.control.mcp.McpHostConfig
import splice.core.mcp.McpToolResultStore
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink

/** Observes the existing child reply after permission-checked client execution; it never executes a tool. */
internal class HostedMcpResults(
    private val server: String,
    private val runtime: McpHostConfig.Runtime,
    private val log: LogSink,
) {
    suspend fun capture(request: JsonObject, answer: JsonObject) {
        val capture = runtime.capture ?: return
        val store = capture.store
        val call = call(request) ?: return
        val result = answer["result"] as? JsonObject
        val error = answer["error"] as? JsonObject
        val captured = withContext(capture.io) {
            when {
                ("result" in answer) == ("error" in answer) -> store.invalidate(call.callbackId)
                result != null -> store.capture(call.callbackId, server, call.tool, call.arguments, result)
                error != null -> store.captureRpcError(call.callbackId, server, call.tool, call.arguments, error)
                else -> store.invalidate(call.callbackId)
            }
        }
        if (captured is McpToolResultStore.Capture.Refused) {
            log("MCP raw result capture refused: ${LogSafe.str(captured.reason)}")
        }
    }

    private fun call(request: JsonObject): Call? {
        if (JsonScalars.str(request, "method") != "tools/call") return null
        val params = request["params"] as? JsonObject ?: return null
        val callbackId = (params["_meta"] as? JsonObject)?.let { JsonScalars.str(it, "claudecode/toolUseId") }
        val tool = JsonScalars.str(params, "name")
        val arguments = if ("arguments" in params) params["arguments"] as? JsonObject else JsonObject(emptyMap())
        return when {
            callbackId == null || tool == null -> null
            arguments == null -> null
            else -> Call(callbackId, tool, arguments)
        }
    }

    private data class Call(val callbackId: String, val tool: String, val arguments: JsonObject)
}
