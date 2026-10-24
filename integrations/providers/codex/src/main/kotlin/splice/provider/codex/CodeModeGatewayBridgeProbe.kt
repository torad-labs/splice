package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeStep

/** Drives typed Claude callback input through the actual turn builder and bridge interceptor. */
internal class CodeModeGatewayBridgeProbe(private val fixture: CodeModeGatewayProbeSupport) {
    private val requests = CodeModeGatewayBridgeRequests(fixture)
    private val runtime = CodeModeGatewayBridgeRuntime(
        ArrayDeque(
            listOf(
                CodeModeStep.Notified("native-progress-${fixture.nonce}"),
                CodeModeStep.Calls(listOf(CodeModeCall("read", "Read", JsonObject(emptyMap())))),
                CodeModeStep.Completed("accepted-native-output-${fixture.nonce}", content = typedContent()),
            ),
        ),
    )
    private val manager = bridge()

    suspend fun replay(): JsonObject {
        try {
            val callback = issue()
            val resumed = resume(callback)
            return buildJsonObject {
                put("callbackId", callback)
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

    private suspend fun issue(): String {
        val first = requests.prepare(manager)
        val sink = CodeModeGatewayBridgeSink()
        checkNotNull(first.roundInterceptor).intercept(JsonWire.string(first.requestBody), sink) {
            RoundResult.Outcome(sourceOutcome())
        }
        return sink.callbackIds.single()
    }

    private suspend fun resume(callback: String): List<JsonObject> =
        posted(manager, callback, "accepted-client-result-${fixture.nonce}")

    private suspend fun posted(
        bridge: CodexCodeModeBridge,
        callback: String,
        result: String,
        tail: String? = null,
    ): List<JsonObject> {
        val request = requests.prepare(bridge, callback, result, tail)
        var posted: String? = null
        checkNotNull(
            request.roundInterceptor,
        ).intercept(JsonWire.string(request.requestBody), CodeModeGatewayBridgeSink()) {
            posted = it
            RoundResult.Outcome(
                TurnOutcome.Success(false, false, noRequestUsage, shape = ResponseShape(messageClosed = true)),
            )
        }
        return fixture.json.parseToJsonElement(checkNotNull(posted)).jsonObject.getValue("input")
            .jsonArray.map { it.jsonObject }
    }

    private fun sourceOutcome(): TurnOutcome.Success {
        val source = "notify('native-progress-${fixture.nonce}'); await tools.Read({}); " +
            "text('accepted-native-output-${fixture.nonce}'); " +
            "image({image_url:'data:image/png;base64,aGVsbG8=',detail:'original'}); " +
            "audio({audio_url:'data:audio/wav;base64,aGVsbG8='});"
        val outer = buildJsonObject {
            put("type", "custom_tool_call"); put("call_id", "outer-${fixture.nonce}")
            put("name", CODE_MODE_TOOL_NAME); put("input", source)
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

    private fun typedContent(): List<JsonObject> = listOf(
        buildJsonObject { put("type", "input_text"); put("text", "accepted-native-output-${fixture.nonce}") },
        buildJsonObject {
            put("type", "input_image"); put("image_url", "data:image/png;base64,aGVsbG8="); put("detail", "original")
        },
        buildJsonObject { put("type", "input_audio"); put("audio_url", "data:audio/wav;base64,aGVsbG8=") },
    )
}
