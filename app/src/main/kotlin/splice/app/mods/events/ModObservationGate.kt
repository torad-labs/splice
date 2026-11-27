package splice.app.mods.events

import splice.app.mods.sessions.ModSessionTracker
import splice.core.turn.TurnObservation
import splice.head.observe.ObservedCredentials
import splice.head.observe.ObservedTurnFacts
import splice.head.observe.TurnObservers

/** An empty roster skips mod projection and history; admission still joins owned launches for session rows. */
internal class ModObservationGate(
    private val sessions: ModSessionTracker,
    private val turns: TurnObservers,
) : TurnObservers by turns {
    override val observingTurns: Boolean get() = sessions.observing && turns.observingTurns

    override fun begin(facts: ObservedTurnFacts): TurnObservation? =
        if (sessions.observing) turns.begin(facts) else null

    override fun begin(facts: ObservedTurnFacts, credentials: ObservedCredentials): TurnObservation? =
        if (sessions.observing) turns.begin(facts, credentials) else null
}
