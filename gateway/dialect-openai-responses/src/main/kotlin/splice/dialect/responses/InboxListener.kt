// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: every overridden callback re-arms request(1)
// itself (a missed re-arm deadlocks the stream silently), and a frame arriving after the round's
// terminal is poisoned rather than served to a later round.
package splice.dialect.responses

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.spi.BufferCapacity
import java.io.IOException
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage

/** Assembles (possibly fragmented) text frames into JSON events and feeds the inbox. Every
 *  overridden callback must re-arm request(1) itself — overriding a Listener method REPLACES the
 *  default that did it (a missed request() deadlocks the stream silently). Binary frames, JSON
 *  that fails to parse, and an overflowing inbox are all protocol anomalies: [onAnomaly] kills the
 *  owning connection (the caller falls back to SSE; NEVER-BELOW-STATUS-QUO). */
internal class InboxListener(
    private val inbox: Channel<JsonObject>,
    private val log: LogSink,
    private val terminalSeen: TerminalSeen,
    /** The socket's vital signs — stamped here on every frame and ping, read on the close line.
     *  Defaulted only for the unit tests that drive a bare listener; WsConnectionFactory always
     *  passes the connection's own (WsUpstreamCloseDiagnosticsTest pins that wiring). Declared
     *  before [onAnomaly] so the factory's trailing lambda still binds to the anomaly seam. */
    private val pulse: WsPulse = WsPulse("ws-?", OpenSockets { 0 }),
    private val onAnomaly: ProtocolAnomaly,
) : WebSocket.Listener {
    private var assembly = StringBuilder()
    private var poisoned = false

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        pulse.frame()
        if (!poisoned) acceptText(data, last)
        if (!poisoned) webSocket.request(1)
        return null
    }

    private fun acceptText(data: CharSequence, last: Boolean) {
        if (terminalSeen()) {
            // A frame after the round's terminal: the round it belongs to is over, so this can only
            // ever be served as some LATER round's first event. Poison instead.
            log("[ws] frame arrived after the round terminal — poisoning rather than serving it later\n")
            onAnomaly()
            return
        }
        assembly.append(data)
        if (assembly.length >= BufferCapacity.MAX_BUFFERED_CHARS) {
            log("[ws] fragmented frame exceeded max buffered size — anomaly\n")
            assembly = StringBuilder()
            poisonFragmentAssembly()
            return
        }
        if (last) deliverAssembly()
    }

    private fun deliverAssembly() {
        val payload = assembly.toString()
        assembly.setLength(0)
        val event = Cancellables.runCatchingCancellable { wsJson.parseToJsonElement(payload).jsonObject }
            .onFailure { log("[ws] unparseable frame (${payload.length} chars) — anomaly\n") }
            .getOrNull()
        if (event == null) {
            onAnomaly()
        } else if (!inbox.trySend(event).isSuccess) {
            log("[ws] inbox overflow/closed — anomaly\n")
            onAnomaly()
        }
    }

    private fun poisonFragmentAssembly() {
        if (poisoned) return
        poisoned = true
        onAnomaly()
    }

    // The server pings every ~20s on a healthy path (probed live 2026-09-02, idle socket held 240s):
    // the ping is the PATH's heartbeat, independent of whether the model has anything to say, which
    // is exactly the fact a close line needs. The JDK answers the pong itself; this override only
    // records the time — and re-arms demand, because overriding onPing REPLACES the default that did.
    override fun onPing(webSocket: WebSocket, message: java.nio.ByteBuffer): CompletionStage<*>? {
        pulse.ping()
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
        log("[ws] unexpected binary frame — anomaly\n")
        onAnomaly() // the protocol is text-JSON; a binary frame means we misunderstand the stream
        webSocket.request(1)
        return null
    }

    // A server-initiated end is a poisoning event, not merely an inbox closure: WsConnection.dead is
    // the pool's ONLY liveness signal, so a close that does not set it leaves the socket registered
    // and acquire() hands it to the NEXT round, which discovers the corpse on send (daemon.log
    // 2026-08-26: 67 "send failed async (IOException: Output closed)" in 17h, each costing a wasted
    // frame plus a reconnect). The inbox is closed FIRST in both paths so the cause shape a waiting
    // round observes is unchanged — kill()'s own close() is then a no-op.
    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        inbox.close()
        log("[ws] ${endOfStream(statusCode, reason)}; ${pulse.describe()} — poisoning the pooled connection\n")
        onAnomaly()
        return null
    }

    /** What this end of stream ACTUALLY licenses us to say.
     *
     *  1006 is not a code any peer can put on the wire (RFC 6455 §7.4.1). The JDK synthesises it
     *  when the TCP stream ends with no close frame at all (WebSocketImpl's receive task calls
     *  onComplete, which signals CLOSED_ABNORMALLY), so it names OUR OBSERVATION and no actor: the
     *  origin, a load balancer, a proxy hop and the network are indistinguishable from this side.
     *  This line used to read "socket closed by the server", and that unearned word is how twelve
     *  routine events a day became an accusation — six of the twelve were sockets our own pool had
     *  left idle for three to twenty-five minutes, which every piece of infrastructure on earth
     *  reaps, and eleven of the twelve killed no round at all (2026-09-02, four-day log: 17 torn
     *  streams in 48,898 turns, and 302 of 303 backend overload verdicts inside ONE incident day).
     *  Any OTHER code did arrive in a real close frame, and that one may be attributed to the peer. */
    private fun endOfStream(statusCode: Int, reason: String): String =
        if (statusCode == ABNORMAL_CLOSURE) {
            "socket stream ended with no close frame (status=1006, actor unknown from here)"
        } else {
            val why = reason.take(ERR_SNIPPET).ifBlank { "no reason given" }
            "socket closed by the peer (status=$statusCode, $why)"
        }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        inbox.close(IOException("websocket error", error))
        log(
            "[ws] socket failed (${error::class.simpleName}; ${pulse.describe()}) — " +
                "poisoning the pooled connection\n",
        )
        onAnomaly()
    }
}

private val wsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// RFC 6455 §7.4.1 reserves 1006: it is never sent, only synthesised by an endpoint that lost the
// stream without a close frame. See [InboxListener.endOfStream].
private const val ABNORMAL_CLOSURE = 1006
