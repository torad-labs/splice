package splice.head.observe

import io.ktor.server.application.ApplicationCall
import splice.core.client.AGENT_HEADER
import splice.core.perf.TurnPerf
import splice.core.turn.ObservedTurn
import splice.core.turn.TurnMeta
import splice.core.turn.TurnObserverOffer
import splice.core.util.WallClock
import splice.head.GatewayTurns
import java.util.UUID

private const val OWNER_HEADER = "x-splice-launch-owner"

// why: the canonical UUID has 32 hex digits and four separators.
private const val OWNER_LENGTH = 36

/** The admitted head is the only one that binds an observation to this perf row. */
internal class TurnObserverAdmission(
    private val observers: TurnObservers,
    private val head: String,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    fun begin(call: ApplicationCall, perf: TurnPerf, meta: TurnMeta) {
        if (observers === NoTurnObservers) return
        val gateway = GatewayTurns.of(call)
        val facts = ObservedTurnFacts(
            sessionId = meta.scope.sessionId,
            launchOwner = if (gateway?.listener?.servedTurn == null) launchOwner(call) else null,
            head = head,
            model = gateway?.asked ?: meta.route.originalModel,
            compact = meta.compact,
            agent = call.request.headers[AGENT_HEADER]?.takeIf(String::isNotBlank),
            atEpochMs = clock(),
        )
        perf.observed = TurnObserverOffer.isolated { observers.begin(facts)?.let { ObservedTurn(it, clock) } }
    }

    private fun launchOwner(call: ApplicationCall): String? {
        val raw = call.request.headers[OWNER_HEADER]?.takeIf { it.length == OWNER_LENGTH } ?: return null
        return try {
            UUID.fromString(raw).toString().takeIf { it.equals(raw, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
