package splice.provider.codex.diagnostics.current

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        check(
            interruption["actionIndex"] == JsonPrimitive(1),
        ) { "Interruption fixture needs its completed baseline cell" }
        if (posted.size == INTERRUPTED_POST_SEQUENCE) {
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
    }
}

// why: one baseline execution and one yielded execution precede the held model continuation.
private const val INTERRUPTED_POST_SEQUENCE = 3
