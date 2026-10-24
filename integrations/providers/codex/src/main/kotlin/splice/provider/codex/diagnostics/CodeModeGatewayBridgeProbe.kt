package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ResponseShape
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CODE_MODE_TOOL_NAME
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeStep

/** Drives typed Claude callback input through the actual turn builder and bridge interceptor. */
internal class CodeModeGatewayBridgeProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val parallelCallbacks: Boolean = false,
) {
    private val requests = CodeModeGatewayBridgeRequests(fixture)
    private val acceptedResult = "accepted-client-result-${fixture.nonce}"
    private val runtime = CodeModeGatewayBridgeRuntime(
        ArrayDeque(
            listOf(
                CodeModeStep.Notified("native-progress-${fixture.nonce}"),
                CodeModeStep.Calls(calls()),
                CodeModeStep.Completed("accepted-native-output-${fixture.nonce}", content = typedContent()),
            ),
        ),
    )
    private val manager = bridge()

    suspend fun replay(): JsonObject {
        try {
            if (parallelCallbacks) CodeModeGatewayBridgeSeed(fixture, requests).run(manager)
            val callbacks = issueMany()
            val request = requests.prepare(
                manager,
                callbacks,
                acceptedResult,
            )
            val resumed = postRequest(request)
            return buildJsonObject {
                put("callbackIds", JsonArray(callbacks.map { JsonPrimitive(it) }))
                put("callbackOnlyPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("postedInput", JsonArray(resumed))
                put("accepted", accepted())
                put("records", snapshots(manager))
            }
        } finally { manager.onProviderStop() }
    }

    suspend fun cleared(): JsonObject {
        try {
            val callback = issue()
            val canonical = resume(callback)
            manager.onProviderStop()
            val restored = bridge()
            try {
                val sent = posted(restored, callback, "[Old tool result content cleared]", "continue-${fixture.nonce}")
                return buildJsonObject {
                    put("callbackId", callback)
                    put("canonical", JsonArray(canonical))
                    put("postedInput", JsonArray(sent))
                    put("accepted", accepted()); put("records", snapshots(restored))
                }
            } finally { restored.onProviderStop() }
        } finally { manager.onProviderStop() }
    }

    suspend fun steering(kind: String): JsonObject {
        try {
            val callback = issue()
            val request = when (kind) {
                "newUser" -> requests.prepare(
                    manager,
                    listOf(callback),
                    acceptedResult,
                    "steer-${fixture.nonce}",
                )
                "editedOldResult" -> {
                    resume(callback)
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
            val sent = postRequest(request)
            return buildJsonObject {
                put("callbackId", callback)
                put("clientPreparedInput", request.requestBody.getValue(CODE_MODE_FIELD_INPUT))
                put("compaction", request.meta.compact)
                put("postedInput", JsonArray(sent))
                put("records", snapshots(manager))
                put("accepted", accepted())
            }
        } finally { manager.onProviderStop() }
    }

    private suspend fun issue(): String = issueMany().single()

    private suspend fun issueMany(): List<String> {
        val first = requests.prepare(manager)
        val sink = CodeModeGatewayBridgeSink()
        checkNotNull(first.roundInterceptor).intercept(JsonWire.string(first.requestBody), sink) {
            RoundResult.Outcome(sourceOutcome())
        }
        return sink.callbackIds.toList()
    }

    private suspend fun resume(callback: String): List<JsonObject> =
        posted(manager, callback, acceptedResult)

    private suspend fun posted(
        bridge: CodexCodeModeBridge,
        callback: String,
        result: String,
        tail: String? = null,
    ): List<JsonObject> {
        val request = requests.prepare(bridge, listOf(callback), result, tail)
        return postRequest(request)
    }

    private suspend fun postRequest(request: BuiltTurn): List<JsonObject> {
        var posted: String? = null
        checkNotNull(
            request.roundInterceptor,
        ).intercept(JsonWire.string(request.requestBody), CodeModeGatewayBridgeSink()) {
            posted = it
            RoundResult.Outcome(
                TurnOutcome.Success(false, false, noRequestUsage, shape = ResponseShape(messageClosed = true)),
            )
        }
        return fixture.json.parseToJsonElement(checkNotNull(posted)).jsonObject.getValue(CODE_MODE_FIELD_INPUT)
            .jsonArray.map { it.jsonObject }
    }

    private fun sourceOutcome(): TurnOutcome.Success {
        val callback = if (parallelCallbacks) {
            "await Promise.all([tools.Read({}),tools.Read({})]);"
        } else {
            "await tools.Read({});"
        }
        val source = "notify('native-progress-${fixture.nonce}'); $callback " +
            "text('accepted-native-output-${fixture.nonce}'); " +
            "image({image_url:'data:image/png;base64,aGVsbG8=',detail:'original'}); " +
            "audio({audio_url:'data:audio/wav;base64,aGVsbG8='});"
        val outer = buildJsonObject {
            put("type", "custom_tool_call"); put("call_id", "outer-${fixture.nonce}")
            put("name", CODE_MODE_TOOL_NAME); put(CODE_MODE_FIELD_INPUT, source)
        }
        val reasoning = checkNotNull(ReasoningReplay.encodeReasoningEnvelope(fixture.native.first().jsonObject))
        return TurnOutcome.Success(
            false,
            false,
            noRequestUsage,
            handoffs = RoundHandoffs(
                customCalls = listOf(GatewayCustomCall("outer-${fixture.nonce}", CODE_MODE_TOOL_NAME, source, outer)),
                reasoningEnvelopes = listOf(reasoning),
            ),
        )
    }

    private fun bridge(): CodexCodeModeBridge = CodexCodeModeBridge(
        CodeModeBridgeConfig({ runtime }, fixture.location, log = fixture.log),
    )

    private fun accepted(): JsonArray = JsonArray(
        runtime.accepted.flatten().map { result ->
            buildJsonObject { put("id", result.id); put("output", result.output); put("isError", result.isError) }
        },
    )

    private fun snapshots(bridge: CodexCodeModeBridge): JsonArray = JsonArray(
        bridge.registry.records.map {
            fixture.json.encodeToJsonElement(CodeModeRecordSnapshot.serializer(), it.snapshot())
        },
    )

    private fun calls(): List<CodeModeCall> = (
        if (parallelCallbacks) {
            listOf(
                "read-one",
                "read-two",
            )
        } else {
            listOf("read")
        }
        )
        .map { CodeModeCall(it, "Read", JsonObject(emptyMap())) }

    private fun typedContent(): List<JsonObject> = listOf(
        buildJsonObject { put("type", "input_text"); put("text", "accepted-native-output-${fixture.nonce}") },
        buildJsonObject {
            put("type", "input_image"); put("image_url", "data:image/png;base64,aGVsbG8="); put("detail", "original")
        },
        buildJsonObject { put("type", "input_audio"); put("audio_url", "data:audio/wav;base64,aGVsbG8=") },
    )
}
