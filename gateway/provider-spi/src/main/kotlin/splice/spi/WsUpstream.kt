// NEW: (ws-transport WS-1, 2026-07-31) the Responses WebSocket transport. The ChatGPT codex
// backend serves the Responses API over a v2 WebSocket (OpenAI-Beta: responses_websockets=…) whose
// payload frames are the SAME JSON events the SSE body carries — one event per text message — so a
// WS round yields the exact Flow<JsonObject> the stream translators already consume. What the WS
// adds over SSE is a REUSABLE connection: the server keeps the previous response's context per
// connection, which is what makes previous_response_id incremental turns possible (live spike
// receipt: gateway/spikes/results/responses-websocket.md).
//
// Laws (campaign ws-transport):
//   NEVER-BELOW-STATUS-QUO — every failure here (connect, busy, send, first-event timeout) surfaces
//   as `null`, and the caller falls back to the SSE full-send path. Only a tear AFTER the first
//   event follows the existing torn-stream rules (the flow throws; the translator's honest
//   terminal owns it), exactly like an SSE body tear today.
//   NO NEW DEPENDENCIES — java.net.http.WebSocket (JDK 21), same lineage as UpstreamClient's Java
//   engine. The dialect stays out of this file: the round-terminal predicate is INJECTED, so
//   provider-spi never learns Responses event names.
package splice.spi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.runCatchingCancellable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One live upstream WebSocket + its inbox. [generation] is the chaining layer's reconnect
 *  detector: a new socket for the same key gets a new generation, and chaining state pinned to an
 *  older generation must full-send (the server's per-connection context died with the socket). */
public class WsConnection internal constructor(
    internal val socket: WebSocket,
    internal val inbox: Channel<JsonObject>,
    public val generation: Long,
    private val log: (String) -> Unit,
) {
    internal val busy = AtomicBoolean(false)
    internal val dead = AtomicBoolean(false)

    /** Poison this connection: no further rounds will reuse it; the socket is torn down hard.
     *  abort(), not sendClose() — the one caller path that lands here is an ABNORMAL end (tear,
     *  timeout, cancelled round, eviction), where a graceful close handshake could block behind
     *  the very stall being escaped. */
    internal fun kill() {
        if (dead.compareAndSet(false, true)) {
            runCatchingCancellable { socket.abort() }
                .onFailure { log("[ws] abort of a torn socket failed (already dead): ${it::class.simpleName}\n") }
            inbox.close()
        }
    }
}

/**
 * The WebSocket upstream: a bounded per-key connection registry plus the one operation the head
 * needs — [round]: send ONE request frame, get the round's events as a Flow, or `null` meaning
 * "use the SSE path for this round" (connect failed / connection busy / first event never came).
 *
 * Reuse contract: a connection is returned to the pool ONLY after a round ends at the injected
 * terminal predicate. A round that ends ANY other way (flow cancelled by the watchdog/client,
 * upstream tear, collector threw) kills the connection — leftover frames from a half-consumed
 * round must never bleed into the next one.
 */
public class WsUpstream(
    private val firstEventTimeoutMs: Long = FIRST_EVENT_TIMEOUT_MS,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val log: (String) -> Unit = {},
    /** Injectable socket factory — tests script a fake WebSocket without a live server. Receives
     *  the wss URI, the handshake headers, and the listener the socket must feed. */
    private val connector: suspend (URI, Map<String, String>, WebSocket.Listener) -> WebSocket =
        { uri, headers, listener -> jdkConnect(uri, headers, listener, CONNECT_TIMEOUT_MS) },
) {

    // LRU by round-completion (touched on successful reuse); oldest evicted at the cap. Same
    // bounded-registry shape as ReasoningCache — one instance per provider, keys are conversation
    // keys, and an evicted entry costs a reconnect (status quo full-send), never an error.
    private val connections = LinkedHashMap<String, WsConnection>()
    private val lock = Any()
    private val generations = AtomicLong(0)

    /**
     * Run one request/response round over the key's WebSocket.
     *
     * Returns `null` — meaning "this round rides SSE instead" — when the connection could not be
     * established, is mid-round (busy), the frame could not be sent, or the first event did not
     * arrive within [firstEventTimeoutMs]. Once the first event HAS arrived, the returned flow is
     * committed: it replays that event and then relays the connection's inbox until [isTerminal]
     * matches (connection returns to the pool) or the stream tears (IOException out of the flow,
     * connection killed — the translator's honest-terminal path owns it from there).
     *
     * [frameFor] runs AFTER the connection is acquired so the chaining layer can pick
     * full-vs-incremental against the live connection's generation and return the frame to send.
     */
    public suspend fun round(
        key: String,
        headers: Map<String, String>,
        wssUrl: String,
        isTerminal: (JsonObject) -> Boolean,
        frameFor: (WsConnection) -> String,
    ): Flow<JsonObject>? {
        val conn = acquire(key, headers, wssUrl) ?: return null
        val first = if (sendFrame(conn, key, frameFor(conn))) awaitFirstEvent(conn, key) else null
        return first?.let { roundFlow(conn, key, it, isTerminal) }
    }

    /** Get-or-connect, and win the busy flag — or null (SSE round). */
    private suspend fun acquire(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val existing = synchronized(lock) { connections[key]?.takeIf { !it.dead.get() } }
        val conn = existing ?: connect(key, headers, wssUrl) ?: return null
        if (!conn.busy.compareAndSet(false, true)) {
            // A concurrent round of the SAME conversation is already on the socket — never
            // interleave two response.create frames on one connection; the second rides SSE.
            log("[ws] $key busy — concurrent round rides SSE\n")
            return null
        }
        // Lost the race with a tear between the registry read and the busy win.
        if (conn.dead.get()) conn.busy.set(false)
        return conn.takeIf { !it.dead.get() }
    }

    private suspend fun connect(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val generation = generations.incrementAndGet()
        val inbox = Channel<JsonObject>(INBOX_CAPACITY)
        val holder = arrayOfNulls<WsConnection>(1)
        val listener = InboxListener(inbox, log) { holder[0]?.kill() }
        val socket = runCatchingCancellable {
            connector(URI.create(wssUrl), headers, listener)
        }.getOrElse { e ->
            log("[ws] $key connect failed (${e::class.simpleName}: ${e.message?.take(ERR_SNIPPET)}) — SSE\n")
            return null
        }
        val conn = WsConnection(socket, inbox, generation, log)
        holder[0] = conn
        val evicted = synchronized(lock) {
            connections.remove(key)?.also { it.kill() } // a dead predecessor never lingers
            connections[key] = conn
            if (connections.size > maxConnections) {
                val oldest = connections.keys.first()
                connections.remove(oldest)
            } else {
                null
            }
        }
        evicted?.kill()
        log("[ws] $key connected (generation=$generation)\n")
        return conn
    }

    private fun sendFrame(conn: WsConnection, key: String, frame: String): Boolean {
        // sendText's async completion is observed by the first-event wait; what is caught here is
        // the SYNCHRONOUS failure class (IllegalStateException: output closed / pending send).
        val sent = runCatchingCancellable { conn.socket.sendText(frame, true) }
            .onFailure { e ->
                log(
                    "[ws] $key send failed (${e::class.simpleName}: ${e.message?.take(ERR_SNIPPET)}) " +
                        "— killing connection, round rides SSE\n",
                )
            }
        if (sent.isFailure) failRound(conn, key)
        return sent.isSuccess
    }

    /** The commit point: no event within the budget → the round (and the connection, whose state
     *  is now indeterminate — the server may or may not have started the response) goes to SSE. */
    private suspend fun awaitFirstEvent(conn: WsConnection, key: String): JsonObject? {
        val first = withTimeoutOrNull(firstEventTimeoutMs) {
            runCatchingCancellable { conn.inbox.receive() }
                .onFailure { log("[ws] $key inbox closed before first event: ${it::class.simpleName}\n") }
                .getOrNull()
        }
        if (first == null) {
            log("[ws] $key no first event in ${firstEventTimeoutMs}ms — killing connection, round rides SSE\n")
            failRound(conn, key)
        }
        return first
    }

    private fun roundFlow(
        conn: WsConnection,
        key: String,
        first: JsonObject,
        isTerminal: (JsonObject) -> Boolean,
    ): Flow<JsonObject> {
        var completed = isTerminal(first)
        return flow {
            emit(first)
            while (!completed) {
                val evt = runCatchingCancellable { conn.inbox.receive() }.getOrElse {
                    throw IOException("websocket stream ended mid-round", it)
                }
                completed = isTerminal(evt)
                emit(evt)
            }
        }.onCompletion { cause ->
            if (cause == null && completed) {
                conn.busy.set(false)
                synchronized(lock) { // touch: completed rounds move their connection to MRU
                    connections.remove(key)?.let { connections[key] = it }
                }
            } else {
                // Cancelled (watchdog/client-gone) or torn: leftover frames of a half-consumed
                // round poison reuse — kill, next round reconnects (full send, status quo).
                failRound(conn, key)
            }
        }
    }

    private fun failRound(conn: WsConnection, key: String) {
        conn.kill()
        synchronized(lock) { if (connections[key] === conn) connections.remove(key) }
    }

    public companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L // same budget as UpstreamClient's TCP connect

        // response.created is an immediate ack (probed live: arrives instantly even ahead of a
        // long prefill), so a missing first event is a broken round, not a slow model.
        private const val FIRST_EVENT_TIMEOUT_MS = 15_000L
        private const val MAX_CONNECTIONS = 32
        private const val ERR_SNIPPET = 160

        // Bounded so a stalled consumer cannot buffer unboundedly; the turn watchdog fires long
        // before a healthy translator falls 1024 events behind. Overflow = kill (InboxListener).
        private const val INBOX_CAPACITY = 1024

        private suspend fun jdkConnect(
            uri: URI,
            headers: Map<String, String>,
            listener: WebSocket.Listener,
            connectTimeoutMs: Long,
        ): WebSocket {
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build()
                .newWebSocketBuilder()
            headers.forEach { (k, v) -> builder.header(k, v) }
            return builder.buildAsync(uri, listener).await()
        }
    }
}

/** Assembles (possibly fragmented) text frames into JSON events and feeds the inbox. Every
 *  overridden callback must re-arm request(1) itself — overriding a Listener method REPLACES the
 *  default that did it (a missed request() deadlocks the stream silently). Binary frames, JSON
 *  that fails to parse, and an overflowing inbox are all protocol anomalies: [onAnomaly] kills the
 *  owning connection (the caller falls back to SSE; NEVER-BELOW-STATUS-QUO). */
internal class InboxListener(
    private val inbox: Channel<JsonObject>,
    private val log: (String) -> Unit,
    private val onAnomaly: () -> Unit,
) : WebSocket.Listener {
    private val assembly = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        assembly.append(data)
        if (last) {
            val payload = assembly.toString()
            assembly.setLength(0)
            val event = runCatchingCancellable { wsJson.parseToJsonElement(payload).jsonObject }
                .onFailure { log("[ws] unparseable frame (${payload.length} chars) — anomaly\n") }
                .getOrNull()
            if (event == null) {
                onAnomaly()
            } else if (!inbox.trySend(event).isSuccess) {
                log("[ws] inbox overflow/closed — anomaly\n")
                onAnomaly()
            }
        }
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
        log("[ws] unexpected binary frame — anomaly\n")
        onAnomaly() // the protocol is text-JSON; a binary frame means we misunderstand the stream
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        inbox.close()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        inbox.close(IOException("websocket error", error))
    }
}

private val wsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
