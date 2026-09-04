// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: none; these are pure contracts, no state and no
// logic. Collected in one file per kt-no-lambda-seam's "one interface per role" rationale — the
// transport's injected ports, with the KDoc that IS the design record of why each one exists.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.WebSocket

/**
 * Opens one upstream WebSocket to [java.net.URI] with the handshake headers, wiring the given
 * listener as the socket's sole consumer.
 *
 * A seam because a live server is not a test dependency: production wires [JdkWebSocketConnector],
 * and a test scripts a fake socket that feeds the listener frames on demand. Suspending because the
 * real one is a network connect with its own timeout.
 */
public fun interface WsConnector {
    public suspend operator fun invoke(
        uri: URI,
        headers: Map<String, String>,
        listener: WebSocket.Listener,
    ): WebSocket
}

/**
 * Whether this event ENDS the round — the caller's dialect knowledge, which the transport does not
 * have and must not guess.
 *
 * The consequence is ownership of the connection: true returns it to the pool for reuse, so a
 * predicate that answers too early strands live frames in the next round's inbox, and one that
 * never answers holds the connection until the stream tears.
 */
public fun interface TerminalEvent {
    public operator fun invoke(event: JsonObject): Boolean
}

/**
 * Builds the frame to SEND, given the live connection it will be sent on.
 *
 * It runs AFTER the connection is acquired, and that ordering is the whole reason it is a function
 * rather than a `String` parameter: the chaining layer picks full-vs-incremental against the
 * acquired connection's GENERATION, which does not exist until the connection does.
 */
public fun interface RoundFrame {
    public operator fun invoke(connection: WsConnection): String
}

/**
 * Whether this connection's round has already seen its terminal event.
 *
 * Read by the frame listener, on the socket's callback thread, to classify a late frame: after the
 * terminal, an arriving frame belongs to a round that is over and cannot be delivered to anyone.
 */
public fun interface TerminalSeen {
    public operator fun invoke(): Boolean
}

/**
 * Reports a protocol anomaly — a binary frame, unparseable JSON, or an overflowing inbox.
 *
 * Not a log call: it KILLS the owning connection, and the caller falls back to SSE. That is the
 * NEVER-BELOW-STATUS-QUO rule made mechanical — a WebSocket that misbehaves costs a reconnect and a
 * slower path, never a failed turn.
 */
public fun interface ProtocolAnomaly {
    public operator fun invoke()
}

/**
 * How many sockets the registry holds right now — read by a dying socket's pulse so the close
 * line can say whether it died alone or as one of forty.
 *
 * A seam because the listener that logs the close is built BEFORE the socket is registered and
 * must never hold the registry itself; the pool answers, under its own lock.
 */
public fun interface OpenSockets {
    public operator fun invoke(): Int
}
