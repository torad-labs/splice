package splice.app.mods.events

import splice.app.mods.run.ModEventHub
import splice.app.mods.sessions.ModLaunches
import splice.app.mods.sessions.ModSessionTracker
import splice.core.turn.TurnObservation
import splice.core.turn.TurnObserverOffer
import splice.core.util.LogSink
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
    log: LogSink = LogSink {},
) : TurnObservers {
    override val offerAudit = TurnObserverOffer(log)
    private val turnPrefix = UUID.randomUUID().toString()
    private val sequence = AtomicLong()

    override val observingTurns: Boolean get() = hub.listening

    override fun sessionSeen(sessionId: String, launchOwner: String?) {
        val launch = launches.forTurn(sessionId, launchOwner)
        if (sessions.observing) sessions.see(sessionId, launch?.owner?.let(launches::stored))
    }

    override fun begin(facts: ObservedTurnFacts): TurnObservation? = project(facts, null)

    override fun begin(facts: ObservedTurnFacts, credentials: ObservedCredentials): TurnObservation? =
        project(facts, credentials)

    private fun project(facts: ObservedTurnFacts, credentials: ObservedCredentials?): TurnObservation? {
        val scrubber = text.snapshot(credentials)
        val session = facts.sessionId?.takeIf(scrubber::safe)
        val launch = launches.forTurn(session, facts.launchOwner)
        val bound = if (facts.launchOwner == null) session?.let(launches::forSession) else null
        if (session != null && bound == null) {
            sessions.see(
                session,
                launch?.owner?.let(launches::stored),
                facts.atEpochMs,
                scrubber = scrubber,
            )
        }
        // A session association may only narrow delivery; it cannot supply labels for an unproved launch.
        val off = (launch ?: bound)?.settings?.modsOff
        if (turnEvents.none { hub.listening(it, off) }) return null
        val labels = scrubber.labels(launch?.settings?.labels.orEmpty())
        val id = "$turnPrefix-${sequence.incrementAndGet()}"
        return ModTurn(hub, off, TurnFields(id, session, labels, facts), scrubber).also { it.started() }
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
