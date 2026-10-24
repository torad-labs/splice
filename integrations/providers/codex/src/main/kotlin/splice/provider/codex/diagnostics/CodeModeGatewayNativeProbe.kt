package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRetention
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult
import splice.upstream.codemode.NativeCodeModeLifecycle
import java.nio.file.Path

/** Real native engines through production gateway history, wait ownership and record retention. */
public object CodeModeGatewayNativeProbe {
    public suspend fun run(
        id: String,
        nonce: String,
        stateDirectory: Path,
        runtime: NativeCodeModeLifecycle,
    ): JsonObject {
        require(Regex("[a-f0-9]{32}").matches(nonce)) { "Expected a fresh 128-bit probe nonce" }
        val fixture = CodeModeGatewayProbeSupport(nonce, stateDirectory)
        val plan = CodeModeGatewayNativePlan(id, nonce)
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                { runtime },
                fixture.location,
                retention = CodeModeRetention(perConversation = 1),
                log = fixture.log,
            ),
        )
        val posted = mutableListOf<JsonArray>()
        try {
            val request = CodeModeGatewayBridgeRequests(fixture).prepare(manager)
            val outcome = checkNotNull(request.roundInterceptor).intercept(
                JsonWire.string(request.requestBody),
                CodeModeGatewayBridgeSink(),
            ) { body ->
                val input = fixture.json.parseToJsonElement(body).jsonObject.getValue(CODE_MODE_FIELD_INPUT).jsonArray
                posted += input
                RoundResult.Outcome(response(plan.next(input)))
            }
            return buildJsonObject {
                put("schema", 1); put("caseId", id); put("nonce", nonce)
                put(
                    "pid",
                    ProcessHandle.current().pid(),
                ); put("scope", "real-native-runtime-through-production-gateway")
                put("retentionPerConversation", 1); put("posted", JsonArray(posted))
                put(
                    "outcome",
                    when (outcome) {
                        is RoundResult.Outcome -> buildJsonObject {
                            put("kind", outcome.outcome::class.simpleName)
                            (outcome.outcome as? TurnOutcome.Failure)?.let { put("message", it.message) }
                        }
                        is RoundResult.Ended -> buildJsonObject { put("kind", "Ended") }
                    },
                )
            }
        } finally { manager.onProviderStop() }
    }

    private fun response(call: GatewayCustomCall?): TurnOutcome.Success = TurnOutcome.Success(
        false,
        false,
        noRequestUsage,
        handoffs = RoundHandoffs(customCalls = listOfNotNull(call)),
    )
}
