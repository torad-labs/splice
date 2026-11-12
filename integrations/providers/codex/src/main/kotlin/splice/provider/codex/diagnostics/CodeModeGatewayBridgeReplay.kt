package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.CODE_MODE_FIELD_INPUT

/** Resume an issued callback through the real request builder and bridge interceptor. */
internal class CodeModeGatewayBridgeReplay(private val state: CodeModeGatewayBridgeProbeState) {
    suspend fun run(): JsonObject {
        try {
            if (state.parallelCallbacks) {
                CodeModeGatewayBridgeSeed(state.fixture, state.requests).run(state.manager)
            }
            val callbacks = state.session.issueMany(state.manager)
            val request = state.requests.prepare(
                state.manager,
                callbacks,
                state.acceptedResult,
            )
            val resumed = state.session.postRequest(request)
            return buildJsonObject {
                put("callbackIds", JsonArray(callbacks.map { JsonPrimitive(it) }))
                put("callbackOnlyPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("postedInput", JsonArray(resumed))
                put("accepted", state.session.accepted())
                put("records", state.session.snapshots(state.manager))
            }
        } finally { state.close() }
    }
}
