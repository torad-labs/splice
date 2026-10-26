// NEW: splice-lead's ruling (Oct 12), closing the chat gap in retry totality: every unknown error gets a bounded
// retry on every dialect, so a chat failure with a vendor code on no list is re-sent once before the user is told.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.turn.TurnOutcome
import splice.upstream.ReanchorPolicy
import splice.upstream.ReanchorRound

/**
 * Re-sends the same request once for an unknown vendor code that arrived before any content: a permanent
 * [FailureCause.Provider.Unrecognized] that carries its round (ChatStreamTranslator attaches the round to the
 * backend's failure only then). The leaf is read, not its permanence alone: a 200 with no events carries its
 * empty round too, under the cause its body names, and a known failure is never sent twice. Not a
 * continuation: nothing reached the client, so the original bytes go again. A second failure, or any other
 * failure, is surfaced as before.
 *
 * "Nothing reached the client" is read off the round, not assumed: the translator flushes a buffered tool
 * call to the client before it decides the turn's end, and its content gate counts only text and thinking,
 * so a round whose error followed a tool call would otherwise be sent again and the client would hold the
 * tool call twice.
 */
public object ChatResendOnce : ReanchorPolicy {
    override fun continuationForFailure(round: ReanchorRound): JsonObject? =
        round.requestBody.takeIf { round.attempt == 0 && resendable(round) }

    private fun resendable(round: ReanchorRound): Boolean {
        val cause = round.failure.cause
        return cause is FailureCause.Provider.Unrecognized && cause.permanent &&
            round.failure.partial?.let(::nothingSent) == true
    }

    private fun nothingSent(round: TurnOutcome.PartialRound): Boolean =
        !round.hasToolUse && !round.text.emittedText && !round.text.emittedThinking
}
