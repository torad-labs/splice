package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CODE_MODE_TOOL_NAME
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeStep

/** Fixed native observations are allowed for the history-component fixture, alongside its exact source handoff. */
internal class CodeModeGatewayBridgeProgram(
    private val fixture: CodeModeGatewayProbeSupport,
    private val parallelCallbacks: Boolean,
) {
    val runtime = CodeModeGatewayBridgeRuntime(
        ArrayDeque(
            listOf(
                CodeModeStep.Notified("native-progress-${fixture.nonce}"),
                CodeModeStep.Calls(calls()),
                CodeModeStep.Completed("accepted-native-output-${fixture.nonce}", content = typedContent()),
            ),
        ),
    )
    fun sourceOutcome(): TurnOutcome.Success {
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
