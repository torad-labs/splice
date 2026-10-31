package splice.provider.codex.callbacks.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin

/** Enriches ordinary client receipts with a lossless reply retained at the MCP transport boundary. */
internal class CodeModeMcpReceipts(private val results: CodeModeMcpResults) {
    fun resolve(turn: CodexCodeModeBridge.Turn, owners: Map<String, CodeModeMcpOwner>): CodexCodeModeBridge.Turn {
        turn.rendering.mcpOriginals = turn.toolResults.filter(::ordinaryOutput).associateBy(CodeModeResult::id)
        return turn.copy(toolResults = turn.toolResults.map { result -> resolve(result, owners) })
    }

    /** File I/O may overlap another branch: accepted receipts and current owner identity take precedence. */
    fun reconcile(
        original: CodexCodeModeBridge.Turn,
        captured: CodexCodeModeBridge.Turn,
        before: Map<String, CodeModeMcpOwner>,
        after: Map<String, CodeModeMcpOwner>,
    ): CodexCodeModeBridge.Turn = original.copy(
        toolResults = original.toolResults.mapIndexed { index, result ->
            val previous = before[result.id]
            val current = after[result.id]
            when {
                !ordinaryOutput(result) -> result
                previous == null || current == null -> result
                current.recordId != previous.recordId -> result
                current.accepted != null -> replay(result, current.accepted)
                current.call != previous.call -> result
                else -> captured.toolResults.getOrNull(index)?.takeIf { it.id == result.id } ?: result
            }
        },
    )

    private fun resolve(result: CodeModeResult, owners: Map<String, CodeModeMcpOwner>): CodeModeResult {
        if (!ordinaryOutput(result)) return result
        val owner = owners[result.id] ?: return result
        return owner.accepted?.let { replay(result, it) } ?: owner.call?.let { captured(result, it) } ?: result
    }

    private fun ordinaryOutput(result: CodeModeResult): Boolean =
        result.origin == CodeModeResultOrigin.GENERIC && result.disposition == CodeModeResultDisposition.TOOL_OUTPUT

    private fun replay(result: CodeModeResult, accepted: CodeModeResult): CodeModeResult {
        // A retained receipt is immutable. Evicting the transport copy cannot change its replay value, and a
        // previously generic receipt never gains a value that was absent when it was accepted.
        val same = accepted.output == result.output && accepted.isError == result.isError
        return if (same && accepted.origin == CodeModeResultOrigin.MCP) {
            result.copy(payload = accepted.payload, origin = accepted.origin)
        } else {
            result
        }
    }

    private fun captured(result: CodeModeResult, call: CodeModeMcpOwner.Call): CodeModeResult {
        val payload = results.read(call.clientId, call.name, call.arguments) ?: return result
        val isError = (payload["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return if (isError == result.isError) {
            result.copy(
                payload = payload,
                origin = CodeModeResultOrigin.MCP,
            )
        } else {
            result
        }
    }
}
