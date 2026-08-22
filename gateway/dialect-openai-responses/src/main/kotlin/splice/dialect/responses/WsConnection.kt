// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: kill() poisons via abort() not sendClose() (an
// abnormal end must never block behind a graceful handshake), and receiveCatching() returns inbox
// closure as a VALUE (see the method doc) so it can never escape past round()'s SSE-fallback null.
package splice.dialect.responses

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.net.http.WebSocket
import java.util.concurrent.atomic.AtomicBoolean

/** One live upstream WebSocket + its inbox. [generation] is the chaining layer's reconnect
 *  detector: a new socket for the same key gets a new generation, and chaining state pinned to an
 *  older generation must full-send (the server's per-connection context died with the socket). */
public class WsConnection internal constructor(
    internal val socket: WebSocket,
    internal val inbox: Channel<JsonObject>,
    public val generation: Long,
    private val log: LogSink,
) {
    internal val busy = AtomicBoolean(false)
    internal val dead = AtomicBoolean(false)

    /** Set by the consumer when the round's terminal event has been taken. From that moment ANY
     *  further frame is a late tail belonging to a finished round, and the listener (the producer)
     *  poisons the connection on arrival. The consumer-side `inbox.isEmpty` check is only a
     *  sample and cannot fence a frame that lands just after it (review of #72); this can, because
     *  it runs on the producer itself. */
    internal val terminalSeen = AtomicBoolean(false)

    /** Poison this connection: no further rounds will reuse it; the socket is torn down hard.
     *  abort(), not sendClose() — the one caller path that lands here is an ABNORMAL end (tear,
     *  timeout, cancelled round, eviction), where a graceful close handshake could block behind
     *  the very stall being escaped. */
    internal fun kill() {
        if (dead.compareAndSet(false, true)) {
            Cancellables.runCatchingCancellable { socket.abort() }
                .onFailure { log("[ws] abort of a torn socket failed (already dead): ${it::class.simpleName}\n") }
            inbox.close()
        }
    }

    /** Receive one event, or null when the inbox is CLOSED.
     *
     *  Why this exists (adversarial review of WS-1, 2026-07-31): `receive()` signals closure by
     *  THROWING ClosedReceiveChannelException, which extends NoSuchElementException -> RuntimeException
     *  and is therefore outside runCatchingCancellable's {IOException, SerializationException,
     *  IllegalArgumentException} (core/util/Cancellables.kt). A server closing cleanly before
     *  answering threw straight out of round(), past withTimeoutOrNull, so the caller never received
     *  the `null` the whole SSE-fallback design depends on — a NEVER-BELOW-STATUS-QUO violation that
     *  surfaced as a client-visible error where SSE would have served the turn.
     *  receiveCatching() returns the closure as a VALUE, so it cannot escape. */
    internal suspend fun receiveCatching(): ChannelResult<JsonObject> = inbox.receiveCatching()
}
