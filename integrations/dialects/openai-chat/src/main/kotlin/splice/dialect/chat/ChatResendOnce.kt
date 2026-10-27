// NEW: splice-lead's ruling (Oct 12), closing the chat gap in retry totality: every unknown error gets a bounded
// retry on every dialect, so a chat failure with a vendor code on no list is re-sent once before the user is told.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.upstream.ReanchorPolicy
import splice.upstream.ReanchorRound

/**
 * The chat head's rule for a round that failed: it can be carried on only by sending the same request again, and
 * only while nothing reached the client. Which failures are sent again, and how many times, is RetryEngine's answer
 * on the turn's one ledger (failure-handling slice 4): an unknown vendor code is sent once more, as splice-lead's
 * ruling of Oct 12 asked, and a known one takes its own row. The translator attaches the round to a failure that
 * arrived before any content (ChatTerminalState.resendsOnce), and a 200 with no events carries its empty round too,
 * under the cause its body names. Not a continuation: nothing reached the client, so the original bytes go again.
 *
 * "Nothing reached the client" is read off the round, not assumed: the translator flushes a buffered tool
 * call to the client before it decides the turn's end, and its content gate counts only text and thinking,
 * so a round whose error followed a tool call would otherwise be sent again and the client would hold the
 * tool call twice.
 */
public object ChatResendOnce : ReanchorPolicy {
    override fun continuationForFailure(round: ReanchorRound): JsonObject? =
        round.requestBody.takeIf { round.failure.partial?.let(::nothingSent) == true }

    private fun nothingSent(round: TurnOutcome.PartialRound): Boolean =
        !round.hasToolUse && !round.text.emittedText && !round.text.emittedThinking
}
