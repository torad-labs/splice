package splice.head.observe

import io.ktor.server.application.ApplicationCall
import splice.core.client.AGENT_HEADER
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.perf.TurnPerf
import splice.core.turn.ObservedTurn
import splice.core.turn.TurnMeta
import splice.core.turn.TurnObserverOffer
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.GatewayTurns
import java.util.UUID

// why: the canonical UUID has 32 hex digits and four separators.
private const val OWNER_LENGTH = 36

/** The admitted head is the only one that binds an observation to this perf row. */
internal class TurnObserverAdmission(
    private val observers: TurnObservers,
    private val head: String,
    log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val offers = observers.offerAudit ?: TurnObserverOffer(log)

    fun begin(call: ApplicationCall, perf: TurnPerf, meta: TurnMeta) {
        val observingTurns = observers.observingTurns
        if (!observingTurns && !observers.observingSessions) return
        val gateway = GatewayTurns.of(call)
        val owner = if (gateway?.listener?.servedTurn == null) launchOwner(call) else null
        val session = meta.scope.sessionId
        if (!observingTurns) {
            if (session != null && owner != null) offers.isolated { observers.sessionSeen(session, owner) }
            return
        }
        val facts = ObservedTurnFacts(
            sessionId = session,
            launchOwner = owner,
            head = head,
            model = gateway?.asked ?: meta.route.originalModel,
            compact = meta.compact,
            agent = call.request.headers[AGENT_HEADER]?.takeIf(String::isNotBlank),
            atEpochMs = clock(),
        )
        perf.observed = offers.isolated {
            observers.begin(facts, ObservedCredentials(call.request.headers))?.let { ObservedTurn(it, clock, offers) }
        }
    }

    private fun launchOwner(call: ApplicationCall): String? {
        val raw = call.request.headers[LAUNCH_OWNER_HEADER]?.takeIf { it.length == OWNER_LENGTH } ?: return null
        return try {
            UUID.fromString(raw).toString().takeIf { it.equals(raw, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
