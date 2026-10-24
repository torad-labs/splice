package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.CODE_MODE_FIELD_INPUT

/** Drives typed Claude callback input through the actual turn builder and bridge interceptor. */
internal class CodeModeGatewayBridgeProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val parallelCallbacks: Boolean = false,
) {
    private val requests = CodeModeGatewayBridgeRequests(fixture)
    private val acceptedResult = "accepted-client-result-${fixture.nonce}"
    private val session = CodeModeGatewayBridgeSession(fixture, requests, parallelCallbacks)
    private val manager = session.bridge()

    suspend fun replay(): JsonObject {
        try {
            if (parallelCallbacks) CodeModeGatewayBridgeSeed(fixture, requests).run(manager)
            val callbacks = session.issueMany(manager)
            val request = requests.prepare(
                manager,
                callbacks,
                acceptedResult,
            )
            val resumed = session.postRequest(request)
            return buildJsonObject {
                put("callbackIds", JsonArray(callbacks.map { JsonPrimitive(it) }))
                put("callbackOnlyPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("postedInput", JsonArray(resumed))
                put("accepted", session.accepted())
                put("records", session.snapshots(manager))
            }
        } finally { manager.onProviderStop() }
    }

    suspend fun cleared(): JsonObject {
        try {
            val callback = session.issueMany(manager).single()
            val canonical = session.posted(manager, callback, acceptedResult)
            manager.onProviderStop()
            val restored = session.bridge()
            try {
                val sent = session.posted(
                    restored,
                    callback,
                    "[Old tool result content cleared]",
                    "continue-${fixture.nonce}",
                )
                return buildJsonObject {
                    put("callbackId", callback)
                    put("canonical", JsonArray(canonical))
                    put("postedInput", JsonArray(sent))
                    put("accepted", session.accepted()); put("records", session.snapshots(restored))
                }
            } finally { restored.onProviderStop() }
        } finally { manager.onProviderStop() }
    }

    suspend fun steering(kind: String): JsonObject {
        try {
            val callback = session.issueMany(manager).single()
            val request = when (kind) {
                "newUser" -> requests.prepare(
                    manager,
                    listOf(callback),
                    acceptedResult,
                    "steer-${fixture.nonce}",
                )
                "editedOldResult" -> {
                    session.posted(manager, callback, acceptedResult)
                    requests.prepare(
                        manager,
                        listOf(callback),
                        "edited-client-result-${fixture.nonce}",
                        "continue-${fixture.nonce}",
                    )
                }
                "rewind" -> requests.prepare(manager, prefix = CodeModeGatewayPrefix.REWOUND)
                "compaction" -> requests.prepare(manager, prefix = CodeModeGatewayPrefix.COMPACTED)
                else -> error("unknown steering fixture $kind")
            }
            val sent = session.postRequest(request)
            return buildJsonObject {
                put("callbackId", callback)
                put("clientPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("compaction", request.meta.compact)
                put("postedInput", JsonArray(sent))
                put("records", session.snapshots(manager))
                put("accepted", session.accepted())
            }
        } finally { manager.onProviderStop() }
    }
}
