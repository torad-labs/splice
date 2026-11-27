package splice.app.mods.events

import splice.app.mods.sessions.ModSessionTracker
import splice.core.turn.TurnObservation
import splice.head.observe.ObservedCredentials
import splice.head.observe.ObservedTurnFacts
import splice.head.observe.TurnObservers

/** An empty roster stops at admission, before launch lookups, session validation, or credential scrubbing. */
internal class ModObservationGate(
    private val sessions: ModSessionTracker,
    private val turns: TurnObservers,
) : TurnObservers by turns {
    override val observingSessions: Boolean get() = sessions.observing
    override val observingTurns: Boolean get() = observingSessions && turns.observingTurns

    override fun sessionSeen(sessionId: String, launchOwner: String?) {
        if (observingSessions) turns.sessionSeen(sessionId, launchOwner)
    }

    override fun begin(facts: ObservedTurnFacts): TurnObservation? =
        if (observingSessions) turns.begin(facts) else null

    override fun begin(facts: ObservedTurnFacts, credentials: ObservedCredentials): TurnObservation? =
        if (observingSessions) turns.begin(facts, credentials) else null
}
