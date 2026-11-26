package splice.app.mods.events

import splice.app.mods.run.ModEventHub
import splice.app.mods.sessions.ModLaunches
import splice.app.mods.sessions.ModSessionTracker
import splice.core.turn.TurnObservation
import splice.head.observe.ObservedCredentials
import splice.head.observe.ObservedTurnFacts
import splice.head.observe.TurnObservers
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class ModTurns(
    private val hub: ModEventHub,
    private val launches: ModLaunches,
    private val sessions: ModSessionTracker,
    private val text: ModEventText,
) : TurnObservers {
    private val turnPrefix = UUID.randomUUID().toString()
    private val sequence = AtomicLong()

    override val observingTurns: Boolean get() = hub.listening

    override fun sessionSeen(sessionId: String, launchOwner: String?) {
        val launch = launches.forTurn(sessionId, launchOwner)
        sessions.see(sessionId, launch?.owner?.let(launches::stored))
    }

    override fun begin(facts: ObservedTurnFacts): TurnObservation? = project(facts, null)

    override fun begin(facts: ObservedTurnFacts, credentials: ObservedCredentials): TurnObservation? =
        project(facts, credentials)

    private fun project(facts: ObservedTurnFacts, credentials: ObservedCredentials?): TurnObservation? {
        val session = facts.sessionId?.takeIf { text.safe(it, credentials) }
        val launch = launches.forTurn(session, facts.launchOwner)
        val stored = launch?.owner?.let(launches::stored)
        if (session != null) sessions.see(session, stored, facts.atEpochMs, credentialIn = credentials)
        val off = launch?.settings?.modsOff
        if (turnEvents.none { hub.listening(it, off) }) return null
        val labels = text.labels(launch?.settings?.labels.orEmpty(), credentials)
        val id = "$turnPrefix-${sequence.incrementAndGet()}"
        return ModTurn(hub, off, TurnFields(id, session, labels, facts, credentials), text).also { it.started() }
    }

    private val turnEvents = listOf(
        "turn.start",
        "request.send",
        "response.receive",
        "stream.event",
        "tool.call",
        "turn.complete",
    )
}
