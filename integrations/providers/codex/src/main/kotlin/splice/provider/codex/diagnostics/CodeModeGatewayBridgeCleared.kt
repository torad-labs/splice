package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.provider.codex.CodexCodeModeBridge

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
                val unaccepted = unaccepted(restored)
                return buildJsonObject {
                    put("callbackId", callback)
                    put("canonical", JsonArray(canonical))
                    put("postedInput", JsonArray(sent))
                    put("accepted", state.session.accepted()); put("records", state.session.snapshots(restored))
                    put("unacceptedClear", unaccepted)
                }
            } finally { restored.onProviderStop() }
        } finally { state.close() }
    }

    private suspend fun unaccepted(restored: CodexCodeModeBridge): JsonObject {
        val callback = "${CODE_MODE_CLIENT_ID_PREFIX}unaccepted-clear-${state.fixture.nonce}"
        val before = state.session.snapshots(restored)
        val old = before.single().jsonObject
        val request = state.requests.prepare(
            restored,
            listOf(callback),
            "[Old tool result content cleared]",
            "continue-unaccepted-${state.fixture.nonce}",
        )
        val posted = state.session.postRequest(request)
        return buildJsonObject {
            put("clientInput", request.requestBody.getValue("input"))
            put("postedInput", JsonArray(posted))
            put("beforeRecords", before); put("afterRecords", state.session.snapshots(restored))
            put("callbackId", callback); put("oldRecordId", old.getValue("id"))
            put("oldOuterCallId", old.getValue("outerCallId"))
        }
    }
}
