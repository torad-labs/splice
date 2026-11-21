// What a listener in front of a head says of a turn it relays, read off the turn's request headers once, as the head
// prepares it (TurnPreparation).
package splice.head

import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import splice.core.client.AGENT_HEADER
import splice.core.client.PARENT_AGENT_HEADER
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.turn.GATHERS_FOR_HEADER
import splice.core.turn.SERVED_NOTICE_HEADER
import splice.core.turn.SERVED_TURN_HEADER
import splice.core.turn.TURN_DOOR_HEADER
import splice.core.turn.TurnAgent
import splice.core.turn.TurnRelay
import splice.core.turn.turnDoors

internal object RelayHeaders {
    /** What a head's relay says of [call]'s turn. A head sits behind a relay that strips these headers from the caller
     *  and sets its own, so its call's headers are the relay's: the server its person prefers among the provider's
     *  upstreams, the id the listener named the turn by (so the turn tells it how it went, server mode's ledger, F5),
     *  the door it came through (desktop-door, decision 7), the session a Context Getter's request works for
     *  (docs/specs/harness.md, decision 9), and the Claude Code agent that sent it (desktop-full, df-turn-headers).
     *
     *  A gateway turn has no relay to strip a caller's headers, so none of the listener's is read off them: the upstream
     *  choice, the served-turn id and the door are the [GatewayTurn]'s own, which the gateway filled from what it
     *  authenticated. What a caller may say stays: the session a gatherer works for and the agent ids, which claim no
     *  more than the session-id header does. */
    fun of(call: ApplicationCall): TurnRelay {
        val headers = call.request.headers
        val turn = GatewayTurns.of(call)
            ?: return TurnRelay(
                preferredUpstream = headers[PREFERRED_UPSTREAM_HEADER],
                servedTurn = headers[SERVED_TURN_HEADER],
                door = headers[TURN_DOOR_HEADER]?.takeIf { it in turnDoors },
                workedFor = workedFor(headers),
                agent = agentOf(headers),
            )
        return TurnRelay(
            preferredUpstream = turn.listener.preferredUpstream,
            servedTurn = turn.listener.servedTurn,
            door = turn.door.takeIf { it in turnDoors },
            workedFor = workedFor(headers),
            agent = agentOf(headers),
        )
    }

    /** The notice a listener sent in place of [call]'s turn, to be answered locally: the relay's header for a head, the
     *  gateway's own value for a gateway turn. */
    fun notice(call: ApplicationCall): String? {
        val turn = GatewayTurns.of(call)
        val said = if (turn == null) call.request.headers[SERVED_NOTICE_HEADER] else turn.listener.notice
        return said?.takeIf(String::isNotBlank)
    }

    private fun workedFor(headers: Headers): String? = headers[GATHERS_FOR_HEADER]?.takeIf { it.isNotBlank() }

    private fun agentOf(headers: Headers): TurnAgent =
        TurnAgent(named(headers, AGENT_HEADER), named(headers, PARENT_AGENT_HEADER))

    private fun named(headers: Headers, name: String): String? = headers[name]?.takeIf(String::isNotBlank)
}
