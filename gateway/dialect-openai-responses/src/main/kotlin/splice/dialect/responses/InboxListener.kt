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
    private val onAnomaly: ProtocolAnomaly,
) : WebSocket.Listener {
    private var assembly = StringBuilder()
    private var poisoned = false

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
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
        log("[ws] socket closed by the server (status=$statusCode) — poisoning the pooled connection\n")
        onAnomaly()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        inbox.close(IOException("websocket error", error))
        log("[ws] socket failed (${error::class.simpleName}) — poisoning the pooled connection\n")
        onAnomaly()
    }
}

private val wsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
