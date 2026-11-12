package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ResponseShape
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult

/** Executes prepared requests through the production bridge and records what it actually posts. */
internal class CodeModeGatewayBridgeSession(
    private val fixture: CodeModeGatewayProbeSupport,
    private val requests: CodeModeGatewayBridgeRequests,
    parallelCallbacks: Boolean,
) {
    private val program = CodeModeGatewayBridgeProgram(fixture, parallelCallbacks)
    suspend fun issueMany(manager: CodexCodeModeBridge): List<String> {
        val first = requests.prepare(manager)
        val sink = CodeModeGatewayBridgeSink()
        checkNotNull(first.roundInterceptor).intercept(JsonWire.string(first.requestBody), sink) {
            RoundResult.Outcome(program.sourceOutcome())
        }
        return sink.callbackIds.toList()
    }

    suspend fun posted(
        bridge: CodexCodeModeBridge,
        callback: String,
        result: String,
        tail: String? = null,
    ): List<JsonObject> {
        val request = requests.prepare(bridge, listOf(callback), result, tail)
        return postRequest(request)
    }

    suspend fun postRequest(request: BuiltTurn): List<JsonObject> {
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

    fun bridge(): CodexCodeModeBridge = CodexCodeModeBridge(
        CodeModeBridgeConfig({ program.runtime }, fixture.location, log = fixture.log),
    )

    fun accepted(): JsonArray = JsonArray(
        program.runtime.accepted.flatten().map { result ->
            buildJsonObject { put("id", result.id); put("output", result.output); put("isError", result.isError) }
        },
    )

    fun snapshots(bridge: CodexCodeModeBridge): JsonArray = JsonArray(
        bridge.registry.records.map {
            fixture.json.encodeToJsonElement(CodeModeRecordSnapshot.serializer(), it.snapshot())
        },
    )
}
