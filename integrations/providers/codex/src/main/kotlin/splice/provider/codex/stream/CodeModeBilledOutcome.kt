package splice.provider.codex.stream

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage

/** Replace the reader's provisional bill with the one durable usage claim owned by this step. */
internal object CodeModeBilledOutcome {
    fun merge(raw: TurnOutcome?, usage: Usage?): TurnOutcome? = when (raw) {
        is TurnOutcome.Success -> raw.copy(usage = usage ?: noRequestUsage)
        null -> usage?.let { TurnOutcome.Success(false, false, it) }
        is TurnOutcome.Failure -> raw.copy(
            // The source's old partial belongs to its own response. The saved script output owns recovery now;
            // re-anchoring this callback-result turn on that partial could hide the failure behind another answer.
            partial = null,
            salvagedUsage = usage ?: noRequestUsage,
        )
        is TurnOutcome.ClientAbandoned -> raw.copy(salvagedUsage = usage ?: noRequestUsage)
    }
}
