// What a listener in front of a head tells it about a turn it relays: server mode's listener and Claude Desktop's
// door each name a turn in request headers, and the head reads them once, as it prepares the turn. Their own value,
// apart from TurnRoute, which says where the turn goes.
package splice.core.turn

/** What the listener that relayed a turn said of it. Every field is null for a turn that came straight to its
 *  head. */
public data class TurnRelay(
    /** The server of the provider's pool this turn's person prefers, by its name in splice.toml (server mode's
     *  `server person set --upstream`). The turn goes there while it is healthy. Null when its person set none, and
     *  for every turn of a provider that is one server. */
    val preferredUpstream: String? = null,
    /** The id the server listener named this turn by ([SERVED_TURN_HEADER]), so its head can tell
     *  the listener how the turn went once its row is written. Null for every turn no listener relayed. */
    val servedTurn: String? = null,
    /** The door the turn came through ([DESKTOP_DOOR]), as the listener that relayed it named it; null for every turn
     *  that came straight to the head. */
    val door: String? = null,
)

/** The header the server listener names a relayed turn in. The relay sets it itself and never passes on a client's
 *  own (ShareRelay). */
public const val SERVED_TURN_HEADER: String = "x-splice-served-turn"

/** The header the server listener hands its head a turn in that the head answers itself, in the conversation, with
 *  the text it carries, sending nothing upstream: a laptop older than `[server.client] minimum` told what to upgrade
 *  to (server mode, F9). The relay sets it itself and never passes on a client's own (ShareRelay). */
public const val SERVED_NOTICE_HEADER: String = "x-splice-served-notice"
