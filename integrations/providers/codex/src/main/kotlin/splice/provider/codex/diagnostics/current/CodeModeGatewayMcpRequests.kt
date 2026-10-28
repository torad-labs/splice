package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn

/** Typed MCP clients enter through the existing bridge API after ordinary catalog-checked permission callbacks.
 * This fixture does not turn a generic Anthropic text result into a raw MCP result. */
internal class CodeModeGatewayMcpRequests(
    fixture: CodeModeGatewayProbeSupport,
    specification: JsonObject,
) {
    private val requests = CodeModeGatewayTypedRequests(fixture, specification)
    private val replies = CodeModeGatewayMcpResults(requests.definitions.map { it.codeModeName }.toSet())

    fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn =
        requests.prepare(manager, callbacks, replies.accepted(callbacks))

    /** Observe actual accepted gateway values, independently of the callback plan's expected payloads. */
    fun report(manager: CodexCodeModeBridge, report: JsonObject): JsonObject = replies.report(manager, report)
}
