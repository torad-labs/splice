package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult

/** Establishes native authority whose model boundaries differ from the client's merged display. */
internal class CodeModeGatewayBridgeSeed(
    private val fixture: CodeModeGatewayProbeSupport,
    private val requests: CodeModeGatewayBridgeRequests,
) {
    suspend fun run(manager: CodexCodeModeBridge) {
        val prepared = requests.prepare(manager)
        val prior = JsonObject(
            fixture.native.first().jsonObject + mapOf(
                "id" to JsonPrimitive("rs-prior-${fixture.nonce}"),
                "encrypted_content" to JsonPrimitive("opaque-prior-${fixture.nonce}"),
            ),
        )
        checkNotNull(prepared.roundInterceptor).intercept(
            JsonWire.string(prepared.requestBody),
            CodeModeGatewayBridgeSink(),
        ) {
            RoundResult.Outcome(
                TurnOutcome.Success(
                    false,
                    false,
                    noRequestUsage,
                    handoffs = RoundHandoffs(nativeOutput = listOf(prior) + fixture.native.drop(1)),
                ),
            )
        }
        requests.earlierReply = true
    }
}
