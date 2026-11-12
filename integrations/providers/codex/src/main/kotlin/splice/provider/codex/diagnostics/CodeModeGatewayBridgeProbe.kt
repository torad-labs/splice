package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonObject

/** Drives typed Claude callback input through the actual turn builder and bridge interceptor. */
internal class CodeModeGatewayBridgeProbe(
    fixture: CodeModeGatewayProbeSupport,
    parallelCallbacks: Boolean = false,
) {
    private val state = CodeModeGatewayBridgeProbeState(fixture, parallelCallbacks)

    suspend fun replay(): JsonObject = CodeModeGatewayBridgeReplay(state).run()

    suspend fun cleared(): JsonObject = CodeModeGatewayBridgeCleared(state).run()

    suspend fun steering(kind: String): JsonObject = CodeModeGatewayBridgeSteeringCase(state).run(kind)
}
