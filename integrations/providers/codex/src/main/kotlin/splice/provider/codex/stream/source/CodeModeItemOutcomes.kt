package splice.provider.codex.stream.source

import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage

/** A shared response has one durable bill, including failure and abandonment terminals. */
internal object CodeModeItemOutcomes {
    fun of(outcome: TurnOutcome, id: String, billingOwner: Boolean): TurnOutcome = when (outcome) {
        is TurnOutcome.Success -> outcome.copy(
            usage = if (billingOwner) outcome.usage else noRequestUsage,
            handoffs = outcome.handoffs.copy(customCalls = outcome.handoffs.customCalls.filter { it.callId == id }),
        )
        is TurnOutcome.Failure -> if (billingOwner) {
            outcome
        } else {
            outcome.copy(
                salvagedUsage = noRequestUsage,
                partial = outcome.partial?.copy(usage = noRequestUsage),
            )
        }
        is TurnOutcome.ClientAbandoned -> if (billingOwner) outcome else outcome.copy(salvagedUsage = noRequestUsage)
    }
}
