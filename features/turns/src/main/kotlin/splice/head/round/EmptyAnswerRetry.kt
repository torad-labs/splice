// NEW: one same-body re-send of a round the model answered with nothing (the empty_model class).
package splice.head.round

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.upstream.RoundBody
import splice.upstream.RoundResult

/**
 * A round whose stream completed with no text, no tool call and no thinking, and no message item either, ends the
 * turn as the overloaded "model returned no content" error today, and the client then re-sends the identical body.
 * Measured on claude-splice over 24 h (2026-10-11): that re-send, 0.5 s later, came back ok 52 times of 84 and
 * empty again 31 times, so the same bytes heal about 63% of the time. Doing that one re-send here costs the client
 * nothing it was not already doing, and nothing has reached the client to duplicate: the structural frames are held
 * until the first model frame, and the round's content-frame counter proves none was written.
 *
 * Exactly one re-send, the same body byte for byte, inside whatever the post itself allows (the watchdog's turn
 * budget is the post's own). A second empty ends as it ends today. A closed empty message is the model's finished
 * answer (codex ends the turn there), so it is never re-sent.
 */
internal class EmptyAnswerRetry(private val perf: TurnPerf?) {
    suspend fun post(body: RoundBody, direct: PostRound): RoundResult {
        val framesBefore = contentFrames()
        val first = direct(body)
        if (!empty(first) || contentFrames() > framesBefore) return first
        perf?.add(PerfKeys.EMPTY_RETRIES, 1)
        return direct(body)
    }

    private fun contentFrames(): Long = perf?.count(PerfKeys.CONTENT_FRAMES_OUT) ?: 0L

    private fun empty(result: RoundResult): Boolean {
        val outcome = (result as? RoundResult.Outcome)?.outcome as? TurnOutcome.Success ?: return false
        return !outcome.hasToolUse && !outcome.incomplete && !outcome.shape.messageClosed &&
            !shown(outcome.text) && outcome.handoffs.customCalls.isEmpty() && outcome.handoffs.toolSearches.isEmpty()
    }

    private fun shown(text: RoundText): Boolean =
        text.emittedText || text.emittedThinking || text.bodyText.isNotBlank() || text.thinkingText.isNotBlank()
}
