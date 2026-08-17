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
package splice.dialect.responses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
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
}

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
        isTerminal: TerminalEvent,
        frameFor: RoundFrame,
    ): Flow<JsonObject>? {
        val conn = acquire(key, headers, wssUrl) ?: return null
        // Between winning the busy flag and handing back a flow there are two suspension points,
        // and a cancellation at either one used to propagate BEFORE any flow existed — so
        // onCompletion never ran, the busy flag stayed set, and every later round for this key lost
        // the CAS and rode SSE forever (review of #72). Any non-flow exit now poisons the
        // connection, which costs one reconnect instead of stranding the conversation.
        var handedOff = false
        return try {
            val first = if (sendFrame(conn, key, frameFor(conn))) awaitFirstEvent(conn, key) else null
            first?.let { roundFlow(conn, key, it, isTerminal) }?.also { handedOff = true }
        } finally {
            if (!handedOff) failRound(conn, key)
        }
    }

    /** The operator-facing key form. The connection key deliberately concatenates the CHAIN key
     *  (the client's session id + conversation identity — raw client-derived text) with the header
     *  digest, and six log sites here interpolated it verbatim into daemon.log while the runner's
     *  own logKey existed for exactly this reason (review of #72, the one finding of it that the
     *  header-digest fix did not finish). Same short stable digest as the runner's: enough to
     *  correlate connect/busy/kill lines for one connection, nothing recoverable. */
    private fun logKey(key: String): String = "ws-" + Integer.toHexString(key.hashCode())

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
    private suspend fun receiveCatching(conn: WsConnection): ChannelResult<JsonObject> =
        conn.inbox.receiveCatching()

    /** Get-or-connect, and win the busy flag — or null (SSE round). */
    private suspend fun acquire(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val existing = synchronized(lock) { connections[key]?.takeIf { !it.dead.get() } }
        val conn = existing ?: connect(key, headers, wssUrl) ?: return null
        if (!conn.busy.compareAndSet(false, true)) {
            // A concurrent round of the SAME conversation is already on the socket — never
            // interleave two response.create frames on one connection; the second rides SSE.
            log("[ws] ${logKey(key)} busy — concurrent round rides SSE\n")
            return null
        }
        // Lost the race with a tear between the registry read and the busy win.
        if (conn.dead.get()) conn.busy.set(false)
        // A NEW round begins: the previous round's terminal must not make this round's first frame
        // look like a late tail. The fence is per-round, and this is the one place a round starts.
        conn.terminalSeen.set(false)
        return conn.takeIf { !it.dead.get() }
    }

    private suspend fun connect(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val generation = generations.incrementAndGet()
        val inbox = Channel<JsonObject>(INBOX_CAPACITY)
        val holder = arrayOfNulls<WsConnection>(1)
        val listener = InboxListener(
            inbox,
            log,
            terminalSeen = { holder[0]?.terminalSeen?.get() == true },
        ) { holder[0]?.kill() }
        val socket = Cancellables.runCatchingCancellable {
            connector(URI.create(wssUrl), headers, listener)
        }.getOrElse { e ->
            log("[ws] ${logKey(key)} connect failed (${e::class.simpleName}: ${e.message?.take(ERR_SNIPPET)}) — SSE\n")
            return null
        }
        val conn = WsConnection(socket, inbox, generation, log)
        holder[0] = conn
        // RACE (review of #72): two callers can both miss the lookup in acquire() and both connect.
        // Replacing unconditionally meant the SECOND registration killed the first caller's socket —
        // which may already have won `busy` and started streaming — aborting a live response. Under
        // the lock we now re-check: a LIVE predecessor wins and our socket is discarded; only a dead
        // one is replaced.
        var winner = conn
        val evicted = synchronized(lock) {
            val existing = connections[key]
            if (existing != null && !existing.dead.get()) {
                winner = existing
                return@synchronized null
            }
            connections.remove(key)?.also { it.kill() } // only ever a DEAD predecessor
            connections[key] = conn
            if (connections.size > maxConnections) {
                // Only an IDLE connection may be evicted: an entry stays registered for the whole
                // of its round, so evicting by pure age could abort an in-flight response
                // (review of #72). With every connection busy nothing is evicted — the cap is a
                // soft bound under burst, and each round poisons its own connection on completion.
                val idle = connections.entries.firstOrNull { !it.value.busy.get() }?.key
                idle?.let { connections.remove(it) }
            } else {
                null
            }
        }
        if (winner !== conn) {
            // Lost the connect race: close our redundant socket and use the live one.
            conn.kill()
            return winner
        }
        evicted?.kill()
        log("[ws] ${logKey(key)} connected (generation=$generation)\n")
        return conn
    }

    /** Send the round's one frame and AWAIT delivery.
     *
     *  Both failure modes are real and neither was caught before the adversarial review of WS-1:
     *  sendText returns a CompletableFuture (javap: `CompletableFuture<WebSocket> sendText(...)`),
     *  so a delivery failure is ASYNCHRONOUS and was previously discarded with the future; and its
     *  SYNCHRONOUS throw is IllegalStateException ("Send pending"/output closed), which
     *  runCatchingCancellable deliberately cannot catch because CancellationException extends
     *  IllegalStateException — catching ISE there would swallow cancellation repo-wide.
     *  Hence the explicit catch with the cancellation rethrow, and the await. */
    private suspend fun sendFrame(conn: WsConnection, key: String, frame: String): Boolean {
        // "sync" vs "async" stays in the log line on purpose: they are different upstream faults
        // (a socket we already broke vs a delivery the peer refused) and only the log distinguishes
        // them after the fact.
        val failure: Pair<String, Throwable>? = try {
            conn.socket.sendText(frame, true).await()
            null
        } catch (e: CancellationException) {
            throw e // a cancelled turn must stop, never look like a send failure
        } catch (e: IllegalStateException) {
            "sync" to e // output closed, or a send already pending on this socket
        } catch (e: IOException) {
            "async" to e // the delivery future completed exceptionally
        }
        if (failure != null) {
            val (kind, error) = failure
            log(
                "[ws] ${logKey(key)} send failed $kind (${error::class.simpleName}: " +
                    "${error.message?.take(ERR_SNIPPET)}) — killing connection, round rides SSE\n",
            )
            failRound(conn, key)
        }
        return failure == null
    }

    /** The commit point: no event within the budget → the round (and the connection, whose state
     *  is now indeterminate — the server may or may not have started the response) goes to SSE.
     *  A CLOSED inbox ends the wait IMMEDIATELY instead of burning the whole budget, and says so
     *  distinguishably: "inbox closed before first event" and "no first event in Nms" are different
     *  upstream faults, and daemon.log is the only place that difference survives. */
    private suspend fun awaitFirstEvent(conn: WsConnection, key: String): JsonObject? {
        val received = withTimeoutOrNull(firstEventTimeoutMs) { receiveCatching(conn) }
        val first = received?.getOrNull()
        if (first == null) {
            val why = if (received == null) {
                "no first event in ${firstEventTimeoutMs}ms"
            } else {
                "inbox closed before first event (${received.exceptionOrNull()?.message?.take(ERR_SNIPPET) ?: "clean"})"
            }
            log("[ws] ${logKey(key)} $why — killing connection, round rides SSE\n")
            failRound(conn, key)
        }
        return first
    }

    private fun roundFlow(
        conn: WsConnection,
        key: String,
        first: JsonObject,
        isTerminal: TerminalEvent,
    ): Flow<JsonObject> {
        var completed = isTerminal(first)
        if (completed) conn.terminalSeen.set(true)
        return flow {
            emit(first)
            while (!completed) {
                // A closed inbox mid-round is a TEAR. It must surface as IOException specifically:
                // TurnDriver's pre-frame reissue keys on `e is IOException` (tearAwareEvents), and
                // the raw ClosedReceiveChannelException this used to throw matched neither that
                // check nor the translators' catch lists (adversarial review of WS-1).
                val received = receiveCatching(conn)
                val evt = received.getOrNull()
                    ?: throw IOException("websocket stream ended mid-round", received.exceptionOrNull())
                completed = isTerminal(evt)
                if (completed) conn.terminalSeen.set(true)
                emit(evt)
            }
        }.onCompletion { cause -> finishRound(conn, key, cause, completed) }
    }

    /** Pool the connection only when the round ended CLEANLY and left nothing behind; otherwise
     *  poison it. Split out of [roundFlow] to stay under the complexity ceiling. */
    private fun finishRound(conn: WsConnection, key: String, cause: Throwable?, completed: Boolean) {
        // The inbox MUST be empty to pool the connection (adversarial review of WS-3): a frame
        // the server emits AFTER the round-ending one would otherwise sit in the inbox and be
        // handed to the NEXT round as its first event — a silent cross-round frame leak, and
        // the reason this class kills rather than drains. Killing costs one reconnect (a full
        // send, i.e. today's behaviour); serving a stale frame corrupts the next turn.
        val drained = conn.inbox.isEmpty
        val poolable = cause == null && completed
        if (poolable && drained) {
            conn.busy.set(false)
            synchronized(lock) { // touch: completed rounds move their connection to MRU
                connections.remove(key)?.let { connections[key] = it }
            }
        } else {
            if (poolable) {
                log("[ws] ${logKey(key)} frames arrived after the round terminal — killing rather than pooling\n")
            }
            // Cancelled (watchdog/client-gone) or torn: leftover frames of a half-consumed
            // round poison reuse — kill, next round reconnects (full send, status quo).
            failRound(conn, key)
        }
    }

    private fun failRound(conn: WsConnection, key: String) {
        conn.kill()
        synchronized(lock) { if (connections[key] === conn) connections.remove(key) }
    }
}

/**
 * The real JDK handshake, behind a type because Kotlin main sources carry no `companion` blocks and
 * this is the DEFAULT VALUE of [WsUpstream.connector] — a default argument is evaluated before the
 * instance exists, so it cannot call an instance member ("Cannot access '<this>' before the instance
 * has been initialized"). A fresh instance per default-argument evaluation is free: the type holds
 * no state, and the HttpClient it builds was already per-call.
 */
private class JdkWebSocketConnector {

    suspend fun jdkConnect(
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

// The transport's budgets, at file scope because Kotlin main sources carry no `companion` blocks.
// Every one was already `private` inside that companion, so nothing gained reach.
private const val CONNECT_TIMEOUT_MS = 10_000L // same budget as UpstreamClient's TCP connect

// response.created is an immediate ack (probed live: arrives instantly even ahead of a
// long prefill), so a missing first event is a broken round, not a slow model.
private const val FIRST_EVENT_TIMEOUT_MS = 15_000L
private const val MAX_CONNECTIONS = 32
private const val ERR_SNIPPET = 160

// Bounded so a stalled consumer cannot buffer unboundedly; the turn watchdog fires long
// before a healthy translator falls 1024 events behind. Overflow = kill (InboxListener).
private const val INBOX_CAPACITY = 1024

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
    private val assembly = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        if (terminalSeen()) {
            // A frame after the round's terminal: the round it belongs to is over, so this can only
            // ever be served as some LATER round's first event. Poison instead.
            log("[ws] frame arrived after the round terminal — poisoning rather than serving it later\n")
            onAnomaly()
            webSocket.request(1)
            return null
        }
        assembly.append(data)
        if (last) {
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
