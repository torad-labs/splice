// NEW: (ws-transport WS-1, 2026-07-31; moved out of :provider-spi 2026-08-01) the Responses
// WebSocket transport. It lives in the DIALECT because the Responses runner is its only caller:
// keeping it in the shared SPI exposed connection-lifecycle details no other module needs
// (review of #72). :gateway still sees only the WsRoundRunner seam, which is all the module
// law lets it see.
// The Responses WebSocket transport. The ChatGPT codex
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
//
// Decomposition campaign (HD-24): WsConnection, WsConnectionPool, WsConnectionFactory,
// WsRoundOpener, WsRoundStream, WsSeams, WsLogKeys, JdkWebSocketConnector and InboxListener all
// moved out of this file into siblings; every relocated member kept its name. This file is now the
// facade — construction and round() — plus the transport's own budgets.
package splice.dialect.responses

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink

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
    private val log: LogSink = LogSink {},
    /** Injectable socket factory — tests script a fake WebSocket without a live server. Receives
     *  the wss URI, the handshake headers, and the listener the socket must feed. */
    private val connector: WsConnector = WsConnector { uri, headers, listener ->
        JdkWebSocketConnector().jdkConnect(uri, headers, listener, CONNECT_TIMEOUT_MS)
    },
) {
    private val logKeys = WsLogKeys()
    private val pool = WsConnectionPool(maxConnections, log, logKeys, connector)
    private val opener = WsRoundOpener(log, logKeys, firstEventTimeoutMs, pool)
    private val stream = WsRoundStream(log, logKeys, pool)

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
        isTerminal: TerminalEvent,
        frameFor: RoundFrame,
    ): Flow<JsonObject>? {
        val conn = pool.acquire(key, headers, wssUrl) ?: return null
        // Between winning the busy flag and handing back a flow there are two suspension points,
        // and a cancellation at either one used to propagate BEFORE any flow existed — so
        // onCompletion never ran, the busy flag stayed set, and every later round for this key lost
        // the CAS and rode SSE forever (review of #72). Any non-flow exit now poisons the
        // connection, which costs one reconnect instead of stranding the conversation.
        var handedOff = false
        return try {
            val first = if (opener.sendFrame(conn, key, frameFor(conn))) opener.awaitFirstEvent(conn, key) else null
            first?.let { stream.roundFlow(conn, key, it, isTerminal) }?.also { handedOff = true }
        } finally {
            if (!handedOff) pool.failRound(conn, key)
        }
    }
}

// The transport's budgets, at file scope because Kotlin main sources carry no `companion` blocks.
// Every one was already `private` inside that companion, so nothing gained reach.
private const val CONNECT_TIMEOUT_MS = 10_000L // same budget as UpstreamClient's TCP connect

// response.created is an immediate ack (probed live: arrives instantly even ahead of a
// long prefill), so a missing first event is a broken round, not a slow model.
private const val FIRST_EVENT_TIMEOUT_MS = 15_000L
private const val MAX_CONNECTIONS = 32
