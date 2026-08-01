// NEW (ws-transport WS-A, 2026-07-31): WsUpstream's lifecycle suite. WsUpstream is the component
// NEVER-BELOW-STATUS-QUO rests on — every failure mode must return null so the caller rides SSE, and
// a round that ends any way other than the terminal predicate must poison its connection so leftover
// frames cannot bleed into the next round. It landed with zero tests.
//
// The fake mirrors java.net.http.WebSocket where it MATTERS, not where it is convenient:
//   * sendText NEVER throws its real failure class — it returns a CompletableFuture that completes
//     exceptionally (JDK contract: the returned future "can complete exceptionally with IOException
//     / IllegalStateException"). A fake that threw synchronously would make the send-failure test
//     green against code that cannot actually observe a send failure.
//   * onClose closes the inbox with NO cause; onError closes it WITH an IOException cause. Those are
//     different shapes, and only the second one ever worked.
//   * frames are delivered on the CALLER's thread, exactly as InboxListener.trySend allows — so the
//     whole suite is deterministic with zero real time and zero threads. runTest's virtual clock is
//     also the instrument for "did this fall back promptly or burn the whole budget".
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.InboxListener
import splice.spi.WsConnection
import splice.spi.WsUpstream
import java.io.IOException
import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

private const val KEY = "conv-1"
private const val WSS_URL = "wss://chatgpt.com/backend-api/codex/responses"
private const val FRAME = """{"type":"response.create","input":[]}"""
private const val BUDGET = 15_000L
private const val CREATED = """{"type":"response.created"}"""
private const val DELTA = """{"type":"response.output_text.delta","delta":"hi"}"""
private const val DONE = """{"type":"response.completed"}"""
private const val CAP = 2

private val HEADERS = mapOf("OpenAI-Beta" to "responses_websockets=v2", "Authorization" to "Bearer t")
private val TERMINAL: (JsonObject) -> Boolean = { it.type() == "response.completed" }

private fun JsonObject.type(): String = this["type"]?.jsonPrimitive?.content ?: "?"

// ------------------------------------------------------------------- the scripted fake socket ---

private class FakeSocket(private val fx: Fixture) : WebSocket {
    lateinit var listener: WebSocket.Listener
    val sent = mutableListOf<String>()
    var aborts = 0
    var requests = 0

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        sent += data.toString()
        fx.sendThrows?.let { throw it }
        fx.reply(this, data.toString())
        return fx.sendFuture(this)
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = ok()

    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = ok()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = ok()

    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = ok()

    override fun request(n: Long) {
        requests += 1
    }

    override fun getSubprotocol(): String = "responses_websockets"

    override fun isOutputClosed(): Boolean = false

    override fun isInputClosed(): Boolean = false

    override fun abort() {
        aborts += 1
    }

    private fun ok(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)

    // --- server-side pushes, delivered the way the JDK delivers them ---

    fun push(json: String) {
        pushRaw(json, last = true)
    }

    fun pushRaw(text: String, last: Boolean) {
        listener.onText(this, text, last)
    }

    fun pushBinary() {
        listener.onBinary(this, ByteBuffer.allocate(1), true)
    }

    /** The shape that used to escape round(): a close with NO cause. */
    fun closeClean() {
        listener.onClose(this, WebSocket.NORMAL_CLOSURE, "bye")
    }

    fun failWith(cause: Throwable) {
        listener.onError(this, cause)
    }
}

// ----------------------------------------------------------------------------- the fixture ---

private class Fixture {
    val opened = mutableListOf<FakeSocket>()
    val handed = mutableListOf<WsConnection>()
    val log = mutableListOf<String>()
    val uris = mutableListOf<URI>()
    val headers = mutableListOf<Map<String, String>>()
    var connects = 0

    var connectFailure: Throwable? = null
    var connectGate: CompletableDeferred<Unit>? = null
    var onHandshake: (FakeSocket) -> Unit = { }
    var reply: (FakeSocket, String) -> Unit = { _, _ -> }
    var sendThrows: Throwable? = null
    var sendFuture: (FakeSocket) -> CompletableFuture<WebSocket> =
        { CompletableFuture.completedFuture(it) }

    lateinit var up: WsUpstream

    private val connector: suspend (URI, Map<String, String>, WebSocket.Listener) -> WebSocket =
        { uri, hdrs, listener ->
            connects += 1
            uris += uri
            headers += hdrs
            connectGate?.await()
            connectFailure?.let { throw it }
            val socket = FakeSocket(this)
            socket.listener = listener
            opened += socket
            listener.onOpen(socket)
            onHandshake(socket)
            socket
        }

    fun start(firstEventTimeoutMs: Long = BUDGET, maxConnections: Int = 32): Fixture {
        up = WsUpstream(
            firstEventTimeoutMs = firstEventTimeoutMs,
            maxConnections = maxConnections,
            log = { log += it },
            connector = connector,
        )
        return this
    }

    suspend fun go(key: String = KEY, frame: String = FRAME): Flow<JsonObject>? =
        up.round(key, HEADERS, WSS_URL, TERMINAL) { conn ->
            handed += conn
            frame
        }

    /** Drive a round to completion and return its event types. */
    suspend fun types(key: String = KEY): List<String> =
        (go(key) ?: error("round $key unexpectedly fell back to SSE")).toList().map { it.type() }

    fun logged(fragment: String): Boolean = log.any { it.contains(fragment) }
}

private fun replyWith(vararg events: String): (FakeSocket, String) -> Unit =
    { socket, _ -> events.forEach { socket.push(it) } }

/**
 * Collect [flow] INCREMENTALLY (never toList(), which would discard everything seen before a
 * mid-round tear) and report the event types seen plus the IOException that ended it.
 */
private suspend fun collectTorn(flow: Flow<JsonObject>): Pair<List<String>, Throwable?> {
    val seen = mutableListOf<String>()
    var torn: Throwable? = null
    try {
        flow.collect { seen += it.type() }
    } catch (e: IOException) {
        torn = e
    }
    return seen to torn
}

// testScheduler/runCurrent/advanceUntilIdle are the virtual clock this suite measures fallback
// PROMPTNESS with (a 15s budget costs 0 real seconds); they carry the coroutines experimental marker.
@OptIn(ExperimentalCoroutinesApi::class)
class WsUpstreamTest {

    // ============================================================ acquire / connect / registry ===

    @Test
    fun `a failed handshake returns null and registers nothing`() = runTest {
        val fx = Fixture().apply { connectFailure = IOException("handshake refused") }.start()
        assertNull(fx.go(), "a failed connect must ride SSE, never surface as an error")
        assertEquals(1, fx.connects)
        assertNull(fx.go(), "still SSE on the second attempt")
        assertEquals(2, fx.connects, "a failed connect leaves NO registry entry to reuse")
        assertTrue(fx.logged("connect failed"), "the failure must be diagnosable from daemon.log")
    }

    @Test
    fun `a malformed wss url falls back instead of throwing`() = runTest {
        val fx = Fixture().start()
        val flow = fx.up.round("k", HEADERS, "wss://host/ path", TERMINAL) { _ -> FRAME }
        assertNull(flow, "URI.create's IllegalArgumentException must degrade to SSE")
        assertEquals(0, fx.connects, "the URL is rejected before the connector is reached")
    }

    @Test
    fun `the handshake carries the url and headers and frameFor gets the LIVE connection`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start()
        assertEquals(listOf("response.completed"), fx.types())
        assertEquals(WSS_URL, fx.uris.single().toString())
        assertEquals(HEADERS, fx.headers.single())
        assertSame(fx.opened.single(), fx.handed.single().socket, "frameFor must see the live connection")
        assertEquals(listOf(FRAME), fx.opened.single().sent, "exactly one frame per round")
    }

    @Test
    fun `a completed round returns the connection to the pool and the next round reuses it`() = runTest {
        val fx = Fixture().apply { reply = replyWith(CREATED, DELTA, DONE) }.start()
        assertEquals(
            listOf("response.created", "response.output_text.delta", "response.completed"),
            fx.types(),
        )
        val generation = fx.handed.single().generation
        assertEquals(3, fx.types().size)
        assertEquals(1, fx.connects, "a healthy connection is REUSED, never reconnected")
        assertSame(fx.handed[0], fx.handed[1])
        assertEquals(generation, fx.handed[1].generation, "reuse must NOT bump the generation")
        assertEquals(0, fx.opened.single().aborts)
    }

    @Test
    fun `a poisoned connection is never reused and the reconnect gets a NEW generation`() = runTest {
        val fx = Fixture().start(firstEventTimeoutMs = BUDGET)
        assertNull(fx.go(), "no first event -> SSE")
        val dead = fx.handed.single()
        assertTrue(dead.dead.get(), "the timed-out connection is poisoned")
        fx.reply = replyWith(DONE)
        assertEquals(listOf("response.completed"), fx.types())
        assertEquals(2, fx.connects)
        assertTrue(
            fx.handed[1].generation > dead.generation,
            "a reconnect MUST bump the generation — it is the chaining layer's only reconnect signal",
        )
    }

    @Test
    fun `a reconnect after any poisoning yields a STRICTLY greater generation`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start()
        val generations = mutableListOf<Long>()
        repeat(3) {
            assertEquals(1, fx.types().size)
            generations += fx.handed.last().generation
            fx.handed.last().kill() // simulate any tear: the next round must reconnect
        }
        assertEquals(3, fx.connects, "each killed connection forces a real reconnect")
        assertTrue(
            generations.zipWithNext().all { (a, b) -> b > a },
            "generations must be STRICTLY increasing, got $generations",
        )
    }

    @Test
    fun `a second round on a key whose round is still in flight returns null`() = runTest {
        val fx = Fixture().apply { reply = replyWith(CREATED) }.start()
        assertNotNull(fx.go(), "the first round commits")
        assertNull(fx.go(), "never two response-create frames on one socket")
        assertEquals(1, fx.connects)
        assertEquals(1, fx.opened.single().sent.size, "the rejected round must not have sent a frame")
        assertTrue(fx.logged("busy"))
    }

    @Test
    fun `KNOWN GAP - a round whose flow is never collected wedges its key busy forever`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start()
        assertNotNull(fx.go(), "the round commits and hands back a flow")
        advanceUntilIdle()
        assertNull(fx.go(), "busy is cleared only by onCompletion, so an abandoned flow pins the key")
        assertEquals(1, fx.connects)
    }

    @Test
    fun `KNOWN GAP - concurrent COLD rounds on one key both connect, the second tears the first`() = runTest {
        val fx = Fixture().apply {
            connectGate = CompletableDeferred()
            reply = replyWith(CREATED)
        }.start()
        val first = async { fx.go() }
        val second = async { fx.go() }
        runCurrent()
        assertTrue(fx.connectGate?.complete(Unit) == true, "both rounds are parked in the handshake")
        val flowA = first.await()
        assertNotNull(second.await(), "the second round also commits")
        assertNotNull(flowA, "both took the existing==null branch — the busy CAS never saw them")
        assertEquals(2, fx.connects, "the busy flag only guards an EXISTING connection")
        assertEquals(1, fx.opened[0].aborts, "the second connect kills the first mid-round")
        val (seen, torn) = collectTorn(flowA ?: error("no flow"))
        assertEquals(listOf("response.created"), seen)
        assertNotNull(torn, "the victim round tears rather than truncating silently")
    }

    // ================================================================================ sendFrame ===

    @Test
    fun `a send that throws synchronously returns null and poisons the connection`() = runTest {
        // IllegalStateException, not IOException: production separates the SYNCHRONOUS throw
        // ("Send pending" / output closed, an ISE) from the ASYNCHRONOUS future failure (IOException),
        // and only ISE exercises this branch. The sibling test covers the future path (review of #72).
        val fx = Fixture().apply { sendThrows = IllegalStateException("Send pending") }.start()
        assertNull(fx.go(), "a failed send must ride SSE")
        assertTrue(fx.logged("send failed sync"), "the SYNCHRONOUS diagnostic, distinct from the async one")
        assertTrue(fx.handed.single().dead.get(), "a failed send poisons the connection")
        assertEquals(1, fx.opened.single().aborts)
        assertTrue(fx.logged("send failed"))
    }

    @Test
    fun `a poisoned connection is DEREGISTERED, not left occupying a registry slot`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start(maxConnections = CAP)
        assertEquals(1, fx.types("a").size) // registry: [a]
        fx.sendThrows = IOException("output closed")
        assertNull(fx.go("b"), "b's send fails") // registry: [a] iff failRound deregisters
        fx.sendThrows = null
        assertEquals(1, fx.types("c").size) // registry: [a, c] — still at cap, no eviction
        assertEquals(
            0,
            fx.opened[0].aborts,
            "a must survive: had the dead b kept its slot, adding c would have evicted a",
        )
    }

    @Test
    fun `a send whose FUTURE fails asynchronously falls back at once, not after the whole budget`() = runTest {
        val fx = Fixture().apply {
            sendFuture = { CompletableFuture.failedFuture(IOException("Output closed")) }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go(), "a failed send must ride SSE")
        val elapsed = testScheduler.currentTime - before
        assertTrue(
            elapsed < BUDGET,
            "sendText reports failure through its FUTURE, never a synchronous throw; unobserved, a dead " +
                "socket costs the whole ${BUDGET}ms first-event budget before falling back (measured ${elapsed}ms)",
        )
        assertTrue(fx.handed.single().dead.get(), "an async send failure poisons the connection")
        assertTrue(fx.logged("send failed async"))
    }

    // ========================================================================== awaitFirstEvent ===

    @Test
    fun `no first event within the budget returns null and poisons the connection`() = runTest {
        val fx = Fixture().start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go())
        assertEquals(BUDGET, testScheduler.currentTime - before, "the wait runs the full budget")
        assertTrue(fx.handed.single().dead.get(), "the connection's state is indeterminate — never reuse it")
        assertEquals(1, fx.opened.single().aborts)
        assertTrue(fx.logged("no first event"))
    }

    @Test
    fun `a CLEAN close before the first event returns null instead of throwing`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ -> socket.closeClean() }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go(), "a server that closes before answering must degrade to SSE, never surface an error")
        assertTrue(
            testScheduler.currentTime - before < BUDGET,
            "a closed inbox must end the wait immediately, not burn the budget",
        )
        assertTrue(fx.handed.single().dead.get())
    }

    @Test
    fun `an ERRORED close before the first event returns null and poisons`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ -> socket.failWith(IOException("connection reset")) }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go())
        assertTrue(testScheduler.currentTime - before < BUDGET, "an errored close ends the wait at once")
        assertTrue(fx.handed.single().dead.get())
        assertTrue(fx.logged("inbox closed before first event"))
    }

    // ================================================================================ roundFlow ===

    @Test
    fun `a terminal FIRST event completes the round with a single emission`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start()
        assertEquals(listOf("response.completed"), fx.types())
        assertFalse(fx.handed.single().dead.get(), "a clean round never poisons")
        assertNotNull(fx.go(), "and the connection went back to the pool")
        assertEquals(1, fx.connects)
    }

    @Test
    fun `events after the terminal one are NOT emitted - the round stops at the predicate`() = runTest {
        val fx = Fixture().apply { reply = replyWith(CREATED, DONE, DELTA) }.start()
        assertEquals(listOf("response.created", "response.completed"), fx.types())
    }

    @Test
    fun `a mid-round CLEAN tear throws IOException and poisons the connection`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ ->
                socket.push(CREATED)
                socket.push(DELTA)
                socket.closeClean()
            }
        }.start()
        val (seen, torn) = collectTorn(fx.go() ?: error("round fell back"))
        assertEquals(listOf("response.created", "response.output_text.delta"), seen, "no silent truncation")
        assertNotNull(torn, "a torn round must throw IOException — the type the torn-stream path owns")
        assertEquals("websocket stream ended mid-round", torn?.message)
        assertTrue(fx.handed.single().dead.get(), "a torn round poisons its connection")
        assertEquals(1, fx.opened.single().aborts)
    }

    @Test
    fun `a mid-round ERRORED tear throws IOException carrying the cause and poisons`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ ->
                socket.push(CREATED)
                socket.failWith(IOException("reset by peer"))
            }
        }.start()
        val (seen, torn) = collectTorn(fx.go() ?: error("round fell back"))
        assertEquals(listOf("response.created"), seen)
        assertEquals("websocket stream ended mid-round", torn?.message)
        assertEquals("websocket error", torn?.cause?.message, "the upstream cause must survive the wrap")
        assertTrue(fx.handed.single().dead.get())
    }

    @Test
    fun `flow cancellation mid-round poisons the connection instead of returning it to the pool`() = runTest {
        val fx = Fixture().apply { reply = replyWith(CREATED) }.start()
        val flow = fx.go() ?: error("round fell back")
        val conn = fx.handed.single()
        val seen = mutableListOf<String>()
        val job = launch { flow.collect { seen += it.type() } }
        runCurrent()
        assertEquals(listOf("response.created"), seen, "the committed first event is emitted before the cancel")
        job.cancelAndJoin()
        assertTrue(conn.dead.get(), "a watchdog-cancelled round must NEVER return its socket to the pool")
        assertEquals(1, fx.opened[0].aborts)
        fx.reply = replyWith(DONE)
        assertEquals(listOf("response.completed"), fx.types(), "and the key is free for a fresh connection")
        assertEquals(2, fx.connects)
    }

    @Test
    fun `a collector that throws mid-round poisons the connection so leftover frames cannot bleed`() = runTest {
        val fx = Fixture().apply { reply = replyWith(CREATED, DELTA, DONE) }.start()
        val flow = fx.go() ?: error("round fell back")
        val conn = fx.handed.single()
        var boom: Throwable? = null
        try {
            flow.collect { throw IllegalArgumentException("translator blew up on ${it.type()}") }
        } catch (e: IllegalArgumentException) {
            boom = e
        }
        assertNotNull(boom, "the collector's failure propagates")
        assertTrue(conn.dead.get(), "DELTA and DONE still sit in the inbox — reuse would hand them to the next round")
        fx.reply = replyWith(DONE)
        assertEquals(listOf("response.completed"), fx.types())
        assertEquals(2, fx.connects, "the poisoned socket must not be reused")
    }

    // ========================================================================= bounded registry ===

    @Test
    fun `the registry evicts and closes the least-recently-used connection past its cap`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start(maxConnections = CAP)
        assertEquals(1, fx.types("a").size)
        assertEquals(1, fx.types("b").size)
        assertEquals(0, fx.opened[0].aborts, "nothing is evicted below the cap")
        assertEquals(1, fx.types("c").size)
        assertEquals(3, fx.connects)
        assertEquals(1, fx.opened[0].aborts, "the oldest connection is evicted AND closed at the cap")
        assertEquals(0, fx.opened[1].aborts)
        assertEquals(0, fx.opened[2].aborts)
        assertEquals(1, fx.types("a").size, "an evicted key reconnects — eviction costs a full send, never an error")
        assertEquals(4, fx.connects)
    }

    @Test
    fun `a completed round moves its connection to MRU so eviction takes the truly-oldest`() = runTest {
        val fx = Fixture().apply { reply = replyWith(DONE) }.start(maxConnections = CAP)
        assertEquals(1, fx.types("a").size)
        assertEquals(1, fx.types("b").size)
        assertEquals(1, fx.types("a").size)
        assertEquals(2, fx.connects, "a was reused, not reconnected")
        assertEquals(1, fx.types("c").size)
        assertEquals(0, fx.opened[0].aborts, "a was touched to MRU by its second round and must survive")
        assertEquals(1, fx.opened[1].aborts, "b is the true LRU")
    }

    // ============================================================================ InboxListener ===

    @Test
    fun `fragmented text frames assemble into ONE event`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ ->
                socket.pushRaw("""{"type":"response.comp""", last = false)
                socket.pushRaw("""leted"}""", last = true)
            }
        }.start()
        assertEquals(listOf("response.completed"), fx.types(), "a fragmented frame is ONE event, not two")
    }

    @Test
    fun `an unparseable text frame is a protocol anomaly and poisons the connection`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ -> socket.pushRaw("not json at all", last = true) }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go(), "an anomaly must ride SSE")
        assertTrue(testScheduler.currentTime - before < BUDGET, "the poisoned inbox ends the wait at once")
        assertTrue(fx.handed.single().dead.get())
        assertTrue(fx.logged("unparseable frame"))
    }

    @Test
    fun `valid JSON that is not an object is a protocol anomaly`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ -> socket.pushRaw("[1,2]", last = true) }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go(), "jsonObject throws IllegalArgumentException on an array — that is an anomaly")
        assertTrue(testScheduler.currentTime - before < BUDGET, "the poisoned inbox ends the wait at once")
        assertTrue(fx.handed.single().dead.get())
        assertTrue(fx.logged("unparseable frame"))
    }

    @Test
    fun `a binary frame is a protocol anomaly and poisons the connection`() = runTest {
        val fx = Fixture().apply {
            reply = { socket, _ -> socket.pushBinary() }
        }.start(firstEventTimeoutMs = BUDGET)
        val before = testScheduler.currentTime
        assertNull(fx.go())
        // The load-bearing assertion. Without it the test passes even when onBinary only LOGS: the
        // round then just times out, which also ends null + poisoned. Promptness is what separates
        // "the anomaly poisoned the connection" from "the budget eventually expired".
        assertTrue(
            testScheduler.currentTime - before < BUDGET,
            "a binary frame must poison at once, not leave the round waiting out the whole budget",
        )
        assertTrue(fx.handed.single().dead.get(), "a binary frame means we misunderstand the stream")
        assertTrue(fx.logged("unexpected binary frame"))
    }

    @Test
    fun `KNOWN GAP - an anomaly during the handshake window cannot poison anything yet`() = runTest {
        val fx = Fixture().apply {
            onHandshake = { socket -> socket.pushBinary() }
            reply = replyWith(DONE)
        }.start()
        assertEquals(listOf("response.completed"), fx.types(), "the round proceeds regardless")
        assertFalse(
            fx.handed.single().dead.get(),
            "pins the gap: onAnomaly fires into a null holder until connect() publishes the connection",
        )
        assertTrue(fx.logged("unexpected binary frame"), "it is logged, just not acted on")
    }
}

/** InboxListener drives the frame->event boundary and is a separate production class; its tests
 *  poke it directly with a small Channel, which is how inbox overflow is a 3-frame test instead of
 *  a 1025-frame one. Split from WsUpstreamTest along the same seam the production code splits on
 *  (detekt LargeClass, threshold 400, DOES analyse test sources here). */
@OptIn(ExperimentalCoroutinesApi::class)
class WsUpstreamInboxListenerTest {
    @Test
    fun `sequential fragmented events do not bleed into each other`() {
        val inbox = Channel<JsonObject>(Channel.UNLIMITED)
        var anomalies = 0
        val socket = FakeSocket(Fixture())
        val listener = InboxListener(
            inbox,
            { },
            terminalSeen = { false },
            onAnomaly = { anomalies += 1 },
        )
        listener.onText(socket, """{"type":"a""", false)
        listener.onText(socket, """"}""", true)
        listener.onText(socket, """{"type":"b""", false)
        listener.onText(socket, """"}""", true)
        assertEquals(0, anomalies, "both events parse — the assembly buffer must reset between them")
        assertEquals("a", inbox.tryReceive().getOrNull()?.type())
        assertEquals("b", inbox.tryReceive().getOrNull()?.type(), "event 2 must not carry event 1's text")
    }

    @Test
    fun `every text frame re-arms request - a missed re-arm deadlocks the stream silently`() {
        val inbox = Channel<JsonObject>(Channel.UNLIMITED)
        val socket = FakeSocket(Fixture())
        val listener = InboxListener(
            inbox,
            { },
            terminalSeen = { false },
            onAnomaly = { },
        )
        listener.onOpen(socket)
        assertEquals(1, socket.requests, "onOpen arms the first read")
        listener.onText(socket, """{"type":"a""", false)
        assertEquals(2, socket.requests, "a NON-final fragment must re-arm too, or assembly stalls forever")
        listener.onText(socket, """"}""", true)
        assertEquals(3, socket.requests)
    }

    @Test
    fun `an inbox that overflows is an anomaly, never a silently dropped frame`() {
        val inbox = Channel<JsonObject>(CAP)
        var anomalies = 0
        val socket = FakeSocket(Fixture())
        val listener = InboxListener(
            inbox,
            { },
            terminalSeen = { false },
            onAnomaly = { anomalies += 1 },
        )
        repeat(CAP + 1) { listener.onText(socket, """{"type":"e$it"}""", true) }
        assertEquals(1, anomalies, "the frame past the buffer poisons the connection")
    }

    @Test
    fun `a frame arriving on a closed inbox is an anomaly`() {
        val inbox = Channel<JsonObject>(CAP)
        var anomalies = 0
        val socket = FakeSocket(Fixture())
        val listener = InboxListener(
            inbox,
            { },
            terminalSeen = { false },
            onAnomaly = { anomalies += 1 },
        )
        inbox.close()
        listener.onText(socket, DONE, true)
        assertEquals(1, anomalies)
    }

    @Test
    fun `onClose closes the inbox with NO cause and onError closes it with an IOException`() = runTest {
        val clean = Channel<JsonObject>(1)
        InboxListener(clean, { }, terminalSeen = { false }, onAnomaly = { })
            .onClose(FakeSocket(Fixture()), WebSocket.NORMAL_CLOSURE, "bye")
        val closed = clean.receiveCatching()
        assertTrue(closed.isClosed)
        assertNull(closed.exceptionOrNull(), "a clean close carries NO cause — this is the shape that used to escape")

        val errored = Channel<JsonObject>(1)
        InboxListener(errored, { }, terminalSeen = { false }, onAnomaly = { })
            .onError(FakeSocket(Fixture()), IOException("reset"))
        val cause = errored.receiveCatching().exceptionOrNull()
        assertTrue(cause is IOException, "an errored close carries an IOException, got $cause")
        assertEquals("websocket error", cause?.message)
    }
}
