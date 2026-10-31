package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn
import java.nio.file.Path

/** Select the explicitly declared client boundary while the probe owns only the round progression. */
internal class CodeModeGatewayScenarioAdapter(
    fixture: CodeModeGatewayProbeSupport,
    specification: JsonObject,
    directory: Path,
) {
    private val ordinary = CodeModeGatewayScenarioRequests(fixture, specification)
    private val availability = if (specification.containsKey("availability")) {
        CodeModeGatewayAvailability(fixture, specification)
    } else {
        null
    }
    private val unavailable = availability?.let { CodeModeGatewayTypedRequests(fixture, specification, it.policy) }
    private val mcp = if (JsonScalars.str(specification, "boundary") == "typed-mcp-delegate") {
        CodeModeGatewayMcpRequests(fixture, specification)
    } else {
        null
    }
    private val freeform = if (JsonScalars.str(specification, "boundary") == "builtin-apply-patch") {
        CodeModeGatewayFreeformRequests(fixture, specification, directory)
    } else {
        null
    }

    suspend fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn =
        freeform?.prepare(
            manager,
            callbacks,
        ) ?: mcp?.prepare(manager, callbacks) ?: unavailable?.prepare(manager, callbacks, emptyList())
            ?: ordinary.prepare(manager, callbacks)

    fun report(manager: CodexCodeModeBridge, report: JsonObject): JsonObject =
        freeform?.report(manager, report) ?: mcp?.report(manager, report) ?: availability?.report(report) ?: report
}
