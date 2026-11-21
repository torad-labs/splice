// What a listener in front of a head says of a turn it relays, read off the turn's request headers once, as the head
// prepares it (TurnPreparation).
package splice.head

import io.ktor.http.Headers
import splice.core.client.AGENT_HEADER
import splice.core.client.PARENT_AGENT_HEADER
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.turn.GATHERS_FOR_HEADER
import splice.core.turn.SERVED_TURN_HEADER
import splice.core.turn.TURN_DOOR_HEADER
import splice.core.turn.TurnAgent
import splice.core.turn.TurnRelay
import splice.core.turn.turnDoors

internal object RelayHeaders {
    /** The server its person prefers among the provider's upstreams, the id the listener named the turn by, so the
     *  turn tells it how it went (server mode's ledger, F5), the door it came through (desktop-door, decision 7), the
     *  session a Context Getter's request works for (docs/specs/harness.md, decision 9), and the Claude Code agent that
     *  sent it (desktop-full, df-turn-headers). */
    fun of(headers: Headers): TurnRelay = TurnRelay(
        preferredUpstream = headers[PREFERRED_UPSTREAM_HEADER],
        servedTurn = headers[SERVED_TURN_HEADER],
        door = headers[TURN_DOOR_HEADER]?.takeIf { it in turnDoors },
        workedFor = headers[GATHERS_FOR_HEADER]?.takeIf { it.isNotBlank() },
        agent = TurnAgent(named(headers, AGENT_HEADER), named(headers, PARENT_AGENT_HEADER)),
    )

    private fun named(headers: Headers, name: String): String? = headers[name]?.takeIf(String::isNotBlank)
}
