package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.CODE_MODE_FIELD_INPUT

/** Exercise steering without changing the replay and cleared-result scenarios. */
internal class CodeModeGatewayBridgeSteeringCase(private val state: CodeModeGatewayBridgeProbeState) {
    suspend fun run(kind: String): JsonObject {
        try {
            val callback = state.session.issueMany(state.manager).single()
            val request = when (kind) {
                "newUser" -> state.requests.prepare(
                    state.manager,
                    listOf(callback),
                    state.acceptedResult,
                    "steer-${state.fixture.nonce}",
                )
                "editedOldResult" -> {
                    state.session.posted(state.manager, callback, state.acceptedResult)
                    state.requests.prepare(
                        state.manager,
                        listOf(callback),
                        "edited-client-result-${state.fixture.nonce}",
                        "continue-${state.fixture.nonce}",
                    )
                }
                "rewind" -> state.requests.prepare(state.manager, prefix = CodeModeGatewayPrefix.REWOUND)
                "compaction" -> state.requests.prepare(state.manager, prefix = CodeModeGatewayPrefix.COMPACTED)
                else -> error("unknown steering fixture $kind")
            }
            val sent = state.session.postRequest(request)
            return buildJsonObject {
                put("callbackId", callback)
                put("clientPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("compaction", request.meta.compact)
                put("postedInput", JsonArray(sent))
                put("records", state.session.snapshots(state.manager))
                put("accepted", state.session.accepted())
            }
        } finally { state.close() }
    }
}
