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
        // status=1006 is the JDK's name for a TCP end WITHOUT a close frame (WebSocketImpl.onComplete),
        // never a status the server sent; the pulse clauses are what say who went quiet first.
        log(
            "[ws] socket closed by the server (status=$statusCode; ${pulse.describe()}) — " +
                "poisoning the pooled connection\n",
        )
        onAnomaly()
        return null
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
