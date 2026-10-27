// NEW: a websocket round the peer closes is served over HTTP when the client has
// seen nothing yet, and says the answer was cut off when it ends the turn after output, with the close in the log.
//
// The Codex outage of 2026-09-25 closed every socket with 1011 after codex.rate_limits and
// codex.response.metadata, before any output. The head read that tear as a truncated round and
// re-anchored it five times on the same transport; Claude Code showed overloaded, and the upstream's
// words never reached it. These drive a real HeadServer whose websocket rounds run the real transport
// (WsUpstream and its listener) against sockets that close exactly that way; the runner is the only
// stand-in, because the Responses runner that owns chaining is internal to its dialect.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.websocket.RoundFrame
import splice.dialect.responses.websocket.TerminalEvent
import splice.dialect.responses.websocket.WsConnector
import splice.dialect.responses.websocket.WsHandshake
import splice.dialect.responses.websocket.WsUpstream
import splice.head.admission.RequestMaterializationGate
import splice.upstream.NEVER_PINGED_MS
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.WsPathPulse
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.transport.UpstreamClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Answers every round's frame with [events] and then closes 1011 with no reason, as the Codex backend
 *  did all evening on 2026-09-25. The JDK delivers both on the socket's thread; so does this. */
private class ClosingSocket(private val events: List<String>, private val listener: WebSocket.Listener) : WebSocket {
    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        events.forEach { listener.onText(this, it, true) }
        listener.onClose(this, INTERNAL_ERROR_CLOSE, "")
        return done()
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = done()

    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = done()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = done()

    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = done()

    override fun request(n: Long) = Unit

    override fun getSubprotocol(): String = ""

    override fun isOutputClosed(): Boolean = false

    override fun isInputClosed(): Boolean = false

    override fun abort() = Unit

    private fun done(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
}

/** A websocket runner over the real transport. Each attempt opens its own key, so every round meets a
 *  fresh socket that closes the same way. */
private class ClosingRunner(events: List<String>, log: LogSink) : WsRoundRunner {
    val attempts = AtomicInteger()
    val bypassed = AtomicInteger()
    private val transport = WsUpstream(
        log = log,
        connector = WsConnector { _, _, listener -> ClosingSocket(events, listener) },
    )

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? {
        val flow = transport.round(
            key = "round-${attempts.incrementAndGet()}",
            handshake = WsHandshake("wss://chatgpt.test/backend-api/codex/responses", emptyMap()),
            isTerminal = TerminalEvent { type(it) in ROUND_ENDS },
            frameFor = RoundFrame { bodyJson },
        ) ?: return null
        return WsRound(flow, WsRoundAbort { }, WsPathPulse { NEVER_PINGED_MS })
    }

    override fun isFailureTerminal(event: JsonObject): Boolean = type(event) in ROUND_FAILS

    override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit

    override fun roundBypassed(meta: TurnMeta) {
        bypassed.incrementAndGet()
    }

    private fun type(event: JsonObject): String = JsonScalars.strOrEmpty(event["type"])
}

private class FixedAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixed")
}

/** The real responses provider with its websocket runner swapped, and nothing else. */
private class ClosingWsProvider(private val inner: Provider, private val runner: WsRoundRunner) : Provider by inner {
    override val wsRunner: WsRoundRunner get() = runner
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsPeerCloseFallbackTest(@param:TempDir private val tmp: Path) {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private var built = 0

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private data class Run(val sse: String, val logs: List<String>, val runner: ClosingRunner, val httpPosts: Int)

    private fun turnAgainst(events: List<String>): Run {
        val logs = CopyOnWriteArrayList<String>()
        val runner = ClosingRunner(events, LogSink { logs += it })
        val head = HeadServer(
            provider = ClosingWsProvider(provider(), runner),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
                log = { logs += it },
                seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
            ).copy(stores = headStores(tmp, suffix = "-${++built}")),
        )
        val before = mock.upstreamBodies.size
        runBlocking { head.start() }
        try {
            val sse = runBlocking {
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    setBody(
                        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                            "messages":[{"role":"user","content":"hi"}]}""",
                    )
                }.bodyAsText()
            }
            return Run(sse, logs.toList(), runner, mock.upstreamBodies.size - before)
        } finally {
            runBlocking { head.stop() }
        }
    }

    private fun provider(): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = FixedAuth(),
            locations = ProviderLocations(baseUrl = mock.baseUrl),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    // Since 2026-10-27 a socket that closes before any output is replaced once at once on the same login, then HTTP
    // (Marcos: "a limit error or close mid-request reconnects at once"). Every socket here closes the same way.
    @Test
    fun `a peer close before any output is tried on one fresh socket, then served over HTTP once`() {
        val run = turnAgainst(CODEX_PREAMBLE)

        assertTrue("ok after auth" in run.sse, "the HTTP answer reaches the client: ${run.sse}")
        assertTrue("message_stop" in run.sse, "as a completed turn: ${run.sse}")
        assertEquals(2, run.runner.attempts.get(), "one fresh-socket retry, never a re-anchor")
        assertEquals(2, run.runner.bypassed.get(), "each round is reported bypassed, so its chain is dropped")
        assertEquals(1, run.httpPosts, "the next attempt went over HTTP, once")
        assertFalse(run.logs.any { "re-anchor" in it }, "no re-anchor: ${run.logs.filter { "re-anchor" in it }}")
        val bypass = run.logs.single { "websocket fallback to HTTP" in it }
        assertTrue("code=pre_output_transport_tear" in bypass, "the bypass line names the fallback reason: $bypass")
        assertTrue("status=1011" in bypass, "the retained detail names the peer close: $bypass")
        assertTrue("codex.rate_limits, codex.response.metadata" in bypass, "and what came before it: $bypass")
        val closes = run.logs.filter { it.startsWith(CLOSE_LINE) }
        assertEquals(2, closes.size, "both sockets closed: $closes")
        assertTrue(
            closes.all { "codex.rate_limits, codex.response.metadata" in it },
            "each close line lists them: $closes",
        )
    }

    @Test
    fun `a peer close after output ends the turn with the close in the client's frame`() {
        val run = turnAgainst(CONTENT_THEN_CLOSE)

        assertTrue("partial words" in run.sse, "the output the client saw stays: ${run.sse}")
        assertEquals(0, run.httpPosts, "a round with output on the wire is never re-served over HTTP")
        // Failure-handling slice 2: the frame says the answer was cut off, in the head's line for a torn answer, and
        // the close code and the peer's reason stay in the log (final-design.md section 10: a line holds no status=).
        assertTrue(TORN_LINE in run.sse, "the frame says the answer was cut off: ${run.sse}")
        assertFalse("status=1011" in run.sse, "the close code is the log's: ${run.sse}")
        val closes = run.logs.filter { it.startsWith(CLOSE_LINE) }
        assertTrue(closes.isNotEmpty() && closes.all { "status=1011" in it }, "the log names the close: $closes")
        assertTrue(closes.all { "no reason given" in it }, "and the peer's reason, or that it gave none: $closes")
    }
}

// RFC 6455 §7.4.1: 1011, the server met a condition that prevented it from fulfilling the request.
private const val INTERNAL_ERROR_CLOSE = 1011

private val ROUND_ENDS = setOf("response.completed", "response.failed", "response.incomplete", "error")
private val ROUND_FAILS = setOf("response.failed", "error")

/** What the Codex backend sent before each 1011 on 2026-09-25 (daemon log, 22:40Z to 23:51Z). */
private val CODEX_PREAMBLE = listOf(
    """{"type":"codex.rate_limits","plan_type":"pro"}""",
    """{"type":"codex.response.metadata","request_id":"req_1"}""",
)

private val CONTENT_THEN_CLOSE = listOf(
    """{"type":"response.created","response":{"id":"r1"}}""",
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""",
    """{"type":"response.output_text.delta","output_index":0,"delta":"partial words"}""",
)

// The transport's own close line; the bypass line quotes the same words, so it is told apart by its start.
private const val CLOSE_LINE = "[ws] socket closed by the peer"

// The head's line for an answer the peer kept cutting off (final-design.md section 10, Torn ending).
private const val TORN_LINE = "[splice] claudex's answer kept getting cut off."
