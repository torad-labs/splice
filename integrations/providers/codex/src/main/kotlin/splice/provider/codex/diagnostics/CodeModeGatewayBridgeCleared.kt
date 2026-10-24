package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Restore a bridge and replay a callback whose client-side result has been cleared. */
internal class CodeModeGatewayBridgeCleared(private val state: CodeModeGatewayBridgeProbeState) {
    suspend fun run(): JsonObject {
        try {
            val callback = state.session.issueMany(state.manager).single()
            val canonical = state.session.posted(state.manager, callback, state.acceptedResult)
            state.close()
            val restored = state.session.bridge()
            try {
                val sent = state.session.posted(
                    restored,
                    callback,
                    "[Old tool result content cleared]",
                    "continue-${state.fixture.nonce}",
                )
                return buildJsonObject {
                    put("callbackId", callback)
                    put("canonical", JsonArray(canonical))
                    put("postedInput", JsonArray(sent))
                    put("accepted", state.session.accepted()); put("records", state.session.snapshots(restored))
                }
            } finally { restored.onProviderStop() }
        } finally { state.close() }
    }
}
