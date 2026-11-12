// NEW: 2026-11-10 — a provider's planned socket rotation is not a failure. The Responses websocket ends every connection
// at 60 minutes with websocket_connection_limit_reached; on Nov 8 a 6.3-hour claudex turn met it six times mid-answer,
// each read as an overload, and the sixth spent the turn's recovery budget and ended it "stayed overloaded". The
// provider's runner says which close is planned (WsRoundRunner.isPlannedClose); this tells the round's failure as the
// rotation it was, which the retry engine continues on its own count and the round controllers continue in silence.
package splice.head.transport

import splice.core.failure.FailureCause
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome

internal object PlannedClose {
    /** [outcome] of a round the provider closed at its planned lifetime: a failure is told as a rotation, its open tool
     *  call kept, since a tool call cut in half still cannot be continued. A round that ended otherwise stands. */
    fun ended(outcome: TurnOutcome): TurnOutcome {
        val failure = outcome as? TurnOutcome.Failure ?: return outcome
        return failure.copy(
            cause = FailureCause.Path.Torn(Tear.ROTATED, toolOpen = failure.partial?.toolTearOpen == true),
        )
    }

    /** Whether [cause] is a planned rotation, which a round controller continues without a line to the person. */
    fun isRotation(cause: FailureCause): Boolean = (cause as? FailureCause.Path.Torn)?.why == Tear.ROTATED
}
