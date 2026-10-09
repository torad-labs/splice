// NEW: retained policy explanations use decoded failure facts, never old retry-type wording.
package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.head.transport.WsFailureTerminal
import splice.head.turn.OutcomeSentences

/** Only an authoritative refusal cause overrides stored words. Provider text adds detail, not provenance. */
internal object TracePolicyRefusal {
    fun sentence(turn: TracedTurn, cause: FailureCause?): String? {
        val refusal = cause == FailureCause.CONTENT_FILTERED || cause == FailureCause.MODEL_REFUSED
        if (cause == null || !refusal) return null
        return OutcomeSentences.of(
            TurnOutcome.Failure(
                message = if (cause == FailureCause.CONTENT_FILTERED) providerMessage(turn).orEmpty() else "",
                cause = cause,
                phase = FailurePhase.TERMINAL,
                providerReported = true,
            ),
        )
    }

    private fun providerMessage(turn: TracedTurn): String? {
        val response = turn.attempts.lastOrNull()?.get("response") as? JsonObject ?: return null
        val text = JsonScalars.str(response, "text") ?: JsonScalars.str(response, "body") ?: return null
        return event(text)?.let { words(it, JsonScalars.long(response, "status")) }
    }

    private fun event(text: String): JsonObject? =
        parse(text) ?: text.lineSequence().mapNotNull { line ->
            parse(line.removePrefix("data:").trim())
        }.lastOrNull()

    private fun words(event: JsonObject, status: Long?): String? {
        val failed = JsonScalars.str(event, "type") in setOf("error", "response.failed") ||
            (status != null && status >= HttpStatus.BAD_REQUEST)
        if (!failed) return null
        val terminal = WsFailureTerminal(event)
        return if (terminal.policyRefusal()) "upstream: ${terminal.code} ${terminal.message}" else null
    }

    private fun parse(text: String): JsonObject? =
        // Malformed retained text supplies no provider words; the decoded cause still explains the refusal.
        JsonScalars.objectOrNull(Json, text)
}
