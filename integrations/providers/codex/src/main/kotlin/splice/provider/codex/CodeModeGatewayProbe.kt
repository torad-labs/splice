package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** Model-free observations of the production history and callback ownership components. */
public object CodeModeGatewayProbe {
    public suspend fun run(id: String, nonce: String, stateDirectory: Path, phase: String): JsonObject {
        require(Regex("[a-f0-9]{32}").matches(nonce)) { "probe nonce must be a fresh 128-bit hexadecimal identity" }
        require(phase in setOf("single", "write", "read")) { "unknown gateway probe phase" }
        val support = CodeModeGatewayProbeSupport(nonce, stateDirectory)
        val observed = when (id) {
            "history.append-only" -> CodeModeGatewayProbeHistory(support).append()
            "history.restart" -> CodeModeGatewayProbeHistory(support).restart(phase)
            "history.replay-is-not-steering" -> CodeModeGatewayBridgeProbe(support, parallelCallbacks = true).replay()
            "history.real-steering" -> CodeModeGatewayBridgeSteering(support).run()
            "history.cleared-results" -> CodeModeGatewayBridgeProbe(support).cleared()
            else -> error("unsupported gateway probe $id")
        }
        return buildJsonObject {
            put("schema", 1)
            put("caseId", id)
            put("nonce", nonce)
            put("phase", phase)
            put("pid", ProcessHandle.current().pid())
            put("scope", "production-provider-components")
            put("observed", observed)
        }
    }
}
