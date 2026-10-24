package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars

/** A terminal carrier can contain later executable items; only the current completed prefix is published at its seal. */
internal object CodeModeItemPrefix {
    fun of(outcome: TurnOutcome.Success?, call: GatewayCustomCall): TurnOutcome.Success? {
        if (outcome == null) return null
        val items = outcome.handoffs.nativeOutput ?: fallback(outcome)
        val boundary = items.indexOfFirst { JsonScalars.str(it, "call_id") == call.callId }
        val prefix = if (boundary >= 0) items.take(boundary + 1) else items + call.raw
        val ids = prefix.mapNotNull { JsonScalars.str(it, "call_id") }.toSet()
        val calls = outcome.handoffs.customCalls.filter { it.callId in ids }
        val leading = prefix.takeWhile { JsonScalars.str(it, "call_id") !in calls.map(GatewayCustomCall::callId) }
        return outcome.copy(
            handoffs = outcome.handoffs.copy(
                customCalls = calls,
                nativePrefix = leading,
                nativeAfterExec = null,
                nativeOutput = prefix,
            ),
        )
    }
    private fun fallback(outcome: TurnOutcome.Success): List<JsonObject> =
        outcome.handoffs.nativePrefix.orEmpty() + outcome.handoffs.customCalls.map(GatewayCustomCall::raw) +
            outcome.handoffs.nativeAfterExec.orEmpty().filterIsInstance<JsonObject>()
}
