// NEW: one same-body re-send of a round the model answered with nothing (the empty_model class).
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.ReanchorPolicy
import splice.upstream.RetryNotice
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
 *
 * A vendor whose empties repeat for the same bytes carries on instead: when the turn's [reanchor] rule builds a
 * continuation from the empty round (its reasoning, then a request for the answer), that request is the one sent.
 * Muse ended 418 of 3,538 turns empty on 2026-10-13, in runs of up to 25 in one session, and the identical re-send
 * healed 39 of 451, so for it the input has to change. Its empties complete with reasoning alone, which the Responses
 * dialect reads as a closed message ([splice.core.turn.ResponseShape.endedInReasoning]), so such a round is offered to
 * the rule first and ends silent, as before, when the rule has no continuation or the request asked for silence.
 */
internal class EmptyAnswerRetry(
    private val perf: TurnPerf?,
    private val reanchor: ReanchorPolicy? = null,
    private val notice: RetryNotice = RetryNotice {},
) {
    suspend fun post(body: RoundBody, direct: PostRound): RoundResult {
        val framesBefore = contentFrames()
        val first = direct(body)
        val emptied = emptied(first)
        if (emptied == null || contentFrames() > framesBefore) return first
        val carried = continuation(body, emptied)
        // reasoning alone is the model's finished silence unless the rule carries it on: never re-sent identically
        if (carried == null && emptied.shape.endedInReasoning) return first
        notice.retry(
            if (carried != null) "continuing after an empty answer" else "retrying an empty answer once",
            "the upstream completed with no answer content",
        )
        perf?.add(if (carried != null) PerfKeys.EMPTY_CONTINUES else PerfKeys.EMPTY_RETRIES, 1)
        return billed(emptied.usage, direct(carried?.let { RoundBody.Tree(it) } ?: body))
    }

    /** The request that carries on from [empty], when the turn's rule has one and [body] is the tree it extends. */
    private fun continuation(body: RoundBody, empty: TurnOutcome.Success): JsonObject? {
        val request = (body as? RoundBody.Tree)?.element as? JsonObject ?: return null
        return reanchor?.continuationForEmpty(request, empty)
    }

    /** The empty round was a request of its own and cost its input: it folds in as an absorbed round, the way the
     *  rollup already meters a superseded round, so the row and the hour count both requests. */
    private fun billed(first: Usage, second: RoundResult): RoundResult {
        val outcome = (second as? RoundResult.Outcome)?.outcome ?: return second
        return RoundResult.Outcome(
            when (outcome) {
                is TurnOutcome.Success -> outcome.copy(usage = first.followedBy(outcome.usage))
                is TurnOutcome.Failure -> outcome.partial?.let { part ->
                    outcome.copy(partial = part.copy(usage = first.followedBy(part.usage)))
                } ?: outcome.copy(salvagedUsage = first.followedBy(outcome.salvagedUsage))
                is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = first.followedBy(outcome.salvagedUsage))
            },
        )
    }

    private fun contentFrames(): Long = perf?.count(PerfKeys.CONTENT_FRAMES_OUT) ?: 0L

    private fun emptied(result: RoundResult): TurnOutcome.Success? {
        val outcome = (result as? RoundResult.Outcome)?.outcome as? TurnOutcome.Success ?: return null
        val closed = outcome.shape.messageClosed && !outcome.shape.endedInReasoning
        val empty = !outcome.hasToolUse && !outcome.incomplete && !closed &&
            !shown(outcome.text) && outcome.handoffs.customCalls.isEmpty() && outcome.handoffs.toolSearches.isEmpty()
        return outcome.takeIf { empty }
    }

    private fun shown(text: RoundText): Boolean =
        text.emittedText || text.emittedThinking || text.bodyText.isNotBlank() || text.thinkingText.isNotBlank()
}
