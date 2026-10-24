package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.provider.codex.history.CodeModeContinuityCapture

/** A terminal carrier can contain later executable items; only the current completed prefix is published at its seal. */
internal object CodeModeItemPrefix {
    fun of(outcome: TurnOutcome.Success?, call: GatewayCustomCall): TurnOutcome.Success? {
        if (outcome == null) return null
        val items = items(outcome)
        val boundary = items.indexOfFirst { JsonScalars.str(it, CALL_ID_FIELD) == call.callId }
        val prefix = if (boundary >= 0) items.take(boundary + 1) else items + call.raw
        val ids = prefix.mapNotNull { JsonScalars.str(it, CALL_ID_FIELD) }.toSet()
        val calls = outcome.handoffs.customCalls.filter { it.callId in ids }
        val leading = prefix.takeWhile { JsonScalars.str(it, CALL_ID_FIELD) !in calls.map(GatewayCustomCall::callId) }
        return outcome.copy(
            handoffs = outcome.handoffs.copy(
                customCalls = calls,
                nativePrefix = outcome.handoffs.nativePrefix ?: leading,
                nativeAfterExec = null,
                nativeOutput = prefix,
            ),
        )
    }
    fun items(outcome: TurnOutcome.Success): List<JsonObject> = outcome.handoffs.nativeOutput ?: fallback(outcome)

    private fun fallback(outcome: TurnOutcome.Success): List<JsonObject> {
        val calls = outcome.handoffs.customCalls
        val after = outcome.handoffs.nativeAfterExec.orEmpty().filterIsInstance<JsonObject>()
        val included = after.mapNotNull { JsonScalars.str(it, CALL_ID_FIELD) }.toSet()
        return (outcome.handoffs.nativePrefix ?: CodeModeContinuityCapture.successItems(outcome)) +
            calls.take(1).map(GatewayCustomCall::raw) + after +
            calls.drop(1).filter { it.callId !in included }.map(GatewayCustomCall::raw)
    }
}

// The native response identity field is shared by prefix boundaries and legacy suffix reconciliation.
private const val CALL_ID_FIELD = "call_id"
