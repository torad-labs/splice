package splice.app.provider.codex

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.names.ClaudeMcpToolNames
import splice.core.mcp.McpToolResultStore
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.provider.codex.callbacks.mcp.CodeModeMcpResults
import splice.provider.codex.callbacks.mcp.CodeModeMcpRpcErrors

/** The transport's retained reply is read only for a callback the head already owns. */
internal class CodeModeMcpReplies(
    private val store: McpToolResultStore,
    private val log: LogSink,
) : CodeModeMcpResults {
    override fun read(callbackId: String, name: String, arguments: JsonObject): JsonObject? =
        when (val found = store.read(callbackId, arguments)) {
            is McpToolResultStore.Read.Found -> named(name, found.receipt)
            McpToolResultStore.Read.Missing -> null
            is McpToolResultStore.Read.Refused -> {
                log("[code-mode] MCP receipt ${LogSafe.str(callbackId)} unavailable: ${LogSafe.str(found.reason)}")
                null
            }
        }

    private fun named(expected: String, receipt: McpToolResultStore.Receipt): JsonObject? =
        if (ClaudeMcpToolNames.name(receipt.server, receipt.tool) == expected) value(receipt) else null

    private fun value(receipt: McpToolResultStore.Receipt): JsonObject? = when (val outcome = receipt.outcome) {
        is McpToolResultStore.Outcome.RawResult -> outcome.value
        is McpToolResultStore.Outcome.RpcError -> CodeModeMcpRpcErrors.result(
            receipt.server,
            receipt.tool,
            outcome.error,
        )
    }
}
