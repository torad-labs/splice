// NEW: one same-body re-send of a round the model answered with nothing (the empty_model class).
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Recovery.AskAgain
import splice.core.failure.WaitLine
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.ReanchorPolicy
import splice.upstream.RoundBody
import splice.upstream.RoundResult
import splice.upstream.retry.How
import splice.upstream.retry.RetryStep

/** What the journal keeps for a reasoning-only answer the turn carried on and that still gave no answer. */
private const val UNANSWERED = "splice: the model finished reasoning without an answer after it was asked for one"

/**
 * A round whose stream completed with no text, no tool call and no thinking, and no message item either, ends the
 * turn as the overloaded "model returned no content" error today, and the client then re-sends the identical body.
 * Measured on claude-splice over 24 h (2026-10-11): that re-send, 0.5 s later, came back ok 52 times of 84 and
 * empty again 31 times, so the same bytes heal about 63% of the time. Doing that one re-send here costs the client
 * nothing it was not already doing, and nothing has reached the client to duplicate: the structural frames are held
 * until the first model frame, and the round's content-frame counter proves none was written.
 *
 * Whether an empty round is sent again, and how, is RetryEngine's Empty row on the turn's one ledger (failure-handling
 * slice 4), asked after every empty round and inside whatever the post itself allows: the same body once, byte for
 * byte, and a reasoning-only answer carried on once. Once the engine stops asking, the round ends as it came back. A
 * closed empty message is the model's finished answer (codex ends the turn there), so it is never re-sent.
 *
 * A vendor whose empties repeat for the same bytes carries on instead: when the turn's [reanchor] rule builds a
 * continuation from the empty round (its reasoning, then a request for the answer), that request is the one sent.
 * Muse ended 418 of 3,538 turns empty on 2026-10-13, in runs of up to 25 in one session, and the identical re-send
 * healed 39 of 451, so for it the input has to change. Its empties complete with reasoning alone, which the Responses
 * dialect reads as a closed message ([splice.core.turn.ResponseShape.endedInReasoning]), so such a round is offered to
 * the rule first and ends silent, as before, when the rule has no continuation or the request asked for silence. Where
 * the rule has one, reasoning alone is the turn left unanswered, not the model's finished silence: once the engine
 * stops carrying it on, the turn ends as the empty answer it is (section 8: CONTINUE_REASONING once, then End, R).
 */
internal class EmptyAnswerRetry(
    private val perf: TurnPerf?,
    /** The turn's steps: whether an empty round is asked again, and how, is the engine's answer. */
    private val steps: RoundSteps,
    private val reanchor: ReanchorPolicy? = null,
    /** Failure-handling slice 2: where the re-send is told, as an empty answer the turn asks again for. */
    private val recovering: Recovering = Recovering {},
) {
    /** [body] posted, then posted again for as long as its round comes back empty and the engine asks again. Every
     *  empty round before the last is billed to the round the turn ends on. */
    suspend fun post(body: RoundBody, direct: PostRound): RoundResult {
        var sent = body
        var spent: Usage? = null
        while (true) {
            val framesBefore = contentFrames()
            val result = direct(sent)
            val empty = emptied(result)?.takeIf { contentFrames() == framesBefore } ?: return billed(spent, result)
            val carried = continuation(sent, empty)
            // Reasoning alone is the model's finished silence unless the rule carries it on: never re-sent identically.
            if (carried == null && empty.shape.endedInReasoning) return billed(spent, result)
            val again = askAgain(sent, empty, carried) ?: return billed(spent, ended(result, empty, carried))
            spent = spent?.followedBy(empty.usage) ?: empty.usage
            sent = again
        }
    }

    /** What the turn sends after [empty], once the engine allows it: the rule's continuation [carried] where the engine
     *  carries a reasoning-only answer on, else the same body; null when the turn ends on the empty answer. */
    private suspend fun askAgain(sent: RoundBody, empty: TurnOutcome.Success, carried: JsonObject?): RoundBody? {
        val cause = FailureCause.Provider.Empty(reasoningOnly = empty.shape.endedInReasoning)
        val step = steps.next(cause, NOTHING_SHOWN, canContinue = false, continuesReasoning = carried != null)
        val again = (step as? RetryStep.Again)?.takeIf { it.how == How.SAME || it.how == How.CONTINUE_REASONING }
            ?: return null
        steps.take(again, cause, NOTHING_SHOWN)
        recovering(WaitLine(cause, AskAgain))
        val next = carried?.takeIf { again.how == How.CONTINUE_REASONING }
        perf?.add(if (next != null) PerfKeys.EMPTY_CONTINUES else PerfKeys.EMPTY_RETRIES, 1)
        return next?.let { RoundBody.Tree(it) } ?: sent
    }

    /** The request that carries on from [empty], when the turn's rule has one and [body] is the tree it extends. */
    private fun continuation(body: RoundBody, empty: TurnOutcome.Success): JsonObject? {
        val request = (body as? RoundBody.Tree)?.element as? JsonObject ?: return null
        return reanchor?.continuationForEmpty(request, empty)
    }

    /** The round the turn ends on once the engine stops asking. A reasoning-only answer the rule would carry on, as
     *  [carried], ends as an empty answer, which the client sends again; any other empty round ends as it came back,
     *  and the pipeline reads it as it reads any round. */
    private fun ended(result: RoundResult, empty: TurnOutcome.Success, carried: JsonObject?): RoundResult {
        if (carried == null || !empty.shape.endedInReasoning) return result
        return RoundResult.Outcome(
            TurnOutcome.Failure(
                cause = FailureCause.Provider.Empty(reasoningOnly = true),
                at = NOTHING_SHOWN,
                evidence = FailureEvidence(UNANSWERED),
                salvagedUsage = empty.usage,
            ),
        )
    }

    /** Each empty round was a request of its own and cost its input: [spent] folds in as absorbed rounds, the way the
     *  rollup already meters a superseded round, so the row and the hour count every request. */
    private fun billed(spent: Usage?, result: RoundResult): RoundResult {
        if (spent == null) return result
        val outcome = (result as? RoundResult.Outcome)?.outcome ?: return result
        return RoundResult.Outcome(
            when (outcome) {
                is TurnOutcome.Success -> outcome.copy(usage = spent.followedBy(outcome.usage))
                is TurnOutcome.Failure -> outcome.partial?.let { part ->
                    outcome.copy(partial = part.copy(usage = spent.followedBy(part.usage)))
                } ?: outcome.copy(salvagedUsage = spent.followedBy(outcome.salvagedUsage))
                is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = spent.followedBy(outcome.salvagedUsage))
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

/** Where an empty round leaves the turn: its stream began and ended, and nothing of it reached the client. */
private val NOTHING_SHOWN = Position(Moment.COMMITTED, streamBegan = true)
