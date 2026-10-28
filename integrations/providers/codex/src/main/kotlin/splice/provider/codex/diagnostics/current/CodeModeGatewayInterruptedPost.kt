package splice.provider.codex.diagnostics.current

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonScalars
import splice.provider.codex.diagnostics.CodeModeGatewayNativeResults
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundResult

/** Holds an actual post-yield continuation so the owner can cancel before any later model action. */
internal class CodeModeGatewayInterruptedPost(
    private val fixture: CodeModeGatewayProbeSupport,
    private val plan: CodeModeGatewayScenarioPlan,
    private val interruption: JsonObject,
) : InterceptedRoundPost {
    val held = CompletableDeferred<Unit>()
    val posted = ArrayList<JsonArray>()

    override suspend fun invoke(bodyJson: String): RoundResult {
        val input = fixture.json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray
        posted += input
        if (posted.size == 2) {
            checkBoundary(input)
            held.complete(Unit)
            awaitCancellation()
        }
        return RoundResult.Outcome(
            TurnOutcome.Success(
                false,
                false,
                noRequestUsage,
                handoffs = RoundHandoffs(customCalls = plan.next(input)),
            ),
        )
    }

    private fun checkBoundary(input: JsonArray) {
        val callId = checkNotNull(JsonScalars.str(interruption, "callId"))
        CodeModeGatewayNativeResults.cell(input, callId)
        val notification = checkNotNull(JsonScalars.str(interruption, "notification"))
        val owned = input.mapNotNull { it as? JsonObject }.filter { JsonScalars.str(it, "call_id") == callId }
        check(
            owned.any { value ->
                JsonScalars.str(value, "name") == "exec" && JsonScalars.str(value, "output") == notification
            },
        ) { "The actual native guest did not deliver its bound interruption notification" }
    }
}
