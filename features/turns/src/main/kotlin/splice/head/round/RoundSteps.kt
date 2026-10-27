// NEW: failure-handling slice 4. The head's round controller asks RetryEngine what follows a round that failed or came
// back empty, on the turn's one ledger, and takes the step it answers here: its spend recorded in the ledger, its wait
// through the turn's waits (final-design.md section 8). The post's attempt loop takes the steps a send can take
// (RetrySteps); the rounds take the ones only a head can: a continuation from what arrived, a reasoning-only answer
// carried on, and the empty answer's re-send. Nothing here counts or plans a retry of its own.
package splice.head.round

import splice.core.failure.FailureCause
import splice.core.failure.Position
import splice.core.turn.TurnOutcome
import splice.core.util.WallClock
import splice.upstream.TurnRetry
import splice.upstream.retry.How
import splice.upstream.retry.RetryEngine
import splice.upstream.retry.RetryStep
import splice.upstream.retry.TurnFacts
import java.time.Instant

/** [retry] is the turn's ledger and waits; [wall] dates the question, as the post's loop dates its own. */
internal class RoundSteps(private val retry: TurnRetry, private val wall: WallClock) {
    /** What follows [cause] met at [at]. [canContinue]: the head's re-anchor rule can carry the turn on from what
     *  arrived; [continuesReasoning]: it carries a reasoning-only answer on. A round never moves the turn to another
     *  login (the post's handoff does, before content) and never waits a stated reset out on a warm session's
     *  allowance, which the post spends, so both read as none here. */
    fun next(cause: FailureCause, at: Position, canContinue: Boolean, continuesReasoning: Boolean = false): RetryStep {
        val facts = TurnFacts(
            pooled = false,
            freeLogin = false,
            warmWaitLeftMs = 0L,
            continuesReasoning = continuesReasoning,
            canContinue = canContinue,
            // The turn's watchdog bounds a round's wait, as it bounds the round itself.
            wallLeftMs = Long.MAX_VALUE,
            earliestLift = null,
        )
        return RetryEngine.next(cause, at, retry.ledger, facts, Instant.ofEpochMilli(wall()))
    }

    /** The step for a failed round the head's re-anchor rule can carry on from what arrived: a continuation, or the
     *  request again where nothing arrived; null where the engine ends the turn, holds it, or names a step only the
     *  post's loop takes (a token exchange, another login). */
    fun recovery(failure: TurnOutcome.Failure): RetryStep.Again? {
        val step = next(failure.cause, failure.at, canContinue = true) as? RetryStep.Again ?: return null
        return step.takeIf { it.how == How.REANCHOR || it.how == How.SAME }
    }

    /** [step], taken for [cause] met at [at]: its budget spent in the turn's ledger, then its wait through the turn's
     *  waits, which tell a long one as it starts. */
    suspend fun take(step: RetryStep.Again, cause: FailureCause, at: Position) {
        retry.ledger.took(step, cause)
        if (step.afterMs > 0L) retry.waits.wait(step.afterMs, cause, at)
    }
}
