// NEW: V4-242 (2026-09-26) — a socket the peer closes says so in the failure it leaves behind.
//
// The Codex outage of 2026-09-25 closed 167 sockets with 1011 right after codex.rate_limits and
// codex.response.metadata, before any output. The status and the reason reached the daemon log and
// nothing else: the listener closed the round's inbox with no cause, so the tear the round threw was a
// bare "websocket stream ended mid-round", and what the client was finally shown held none of the
// upstream's words. These run the real transport (WsUpstream, InboxListener, WsRoundStream) against a
// socket that answers exactly that way.
package campaign.v4242

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.util.LogSink
import splice.dialect.responses.websocket.RoundFrame
import splice.dialect.responses.websocket.TerminalEvent
import splice.dialect.responses.websocket.WsConnector
import splice.dialect.responses.websocket.WsUpstream
import java.io.IOException
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class PeerCloseNamesItselfTest {
    /** Answers each round's frame with [events], then closes with [status] and [reason] when [close]
     *  is set — the JDK delivers both on the socket's own thread, one callback at a time, as here. */
    private class AnsweringSocket(
        private val events: List<String>,
        private val status: Int,
        private val reason: String,
        private val close: Boolean,
    ) : WebSocket {
        lateinit var listener: WebSocket.Listener

        override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
            events.forEach { listener.onText(this, it, true) }
            if (close) listener.onClose(this, status, reason)
            return CompletableFuture.completedFuture(this)
        }

        /** The peer ends a socket that sits idle in the pool, between rounds. */
        fun closeIdle() {
            listener.onClose(this, status, reason)
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

    private val lines = CopyOnWriteArrayList<String>()

    private fun transport(socket: AnsweringSocket): WsUpstream = WsUpstream(
        log = LogSink { lines += it },
        connector = WsConnector { _, _, listener -> socket.also { it.listener = listener } },
    )

    private suspend fun round(ws: WsUpstream) = checkNotNull(
        ws.round(
            key = "conversation",
            headers = emptyMap(),
            wssUrl = WSS_URL,
            isTerminal = TerminalEvent { it["type"].toString().trim('"') == "response.completed" },
            frameFor = RoundFrame { "{}" },
        ),
    ) { "the first event arrived, so the round is committed to the websocket" }

    private fun words(e: Throwable): String =
        generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `a peer close before any output names its status, its reason and the events before it`() {
        val ws = transport(AnsweringSocket(CODEX_PREAMBLE, status = 1011, reason = "", close = true))
        val torn = assertThrows<IOException> { runBlocking { round(ws).collect() } }

        val said = words(torn)
        assertTrue("status=1011" in said, "the tear carries the close code: $said")
        assertTrue("no reason given" in said, "and says the peer gave no reason: $said")
        assertTrue(
            "codex.rate_limits, codex.response.metadata" in said,
            "and names the events that arrived before the close: $said",
        )
        val close = lines.single { it.startsWith(CLOSE_LINE) }
        assertTrue(
            "codex.rate_limits, codex.response.metadata" in close,
            "the close line lists the event types the round saw before it: $close",
        )
    }

    @Test
    fun `a peer close with a reason carries the reason into the tear`() {
        val socket = AnsweringSocket(CODEX_PREAMBLE, status = 1011, reason = "upstream unavailable", close = true)
        val ws = transport(socket)
        val torn = assertThrows<IOException> { runBlocking { round(ws).collect() } }

        val said = words(torn)
        assertTrue("status=1011, upstream unavailable" in said, said)
    }

    @Test
    fun `an idle close lists nothing from the round that already finished`() {
        val socket = AnsweringSocket(COMPLETED_ROUND, status = 1011, reason = "", close = false)
        val ws = transport(socket)
        runBlocking { round(ws).collect() }

        socket.closeIdle()

        val close = lines.single { it.startsWith(CLOSE_LINE) }
        assertTrue("status=1011" in close, close)
        assertFalse("response.created" in close, "a finished round's events belong to no close: $close")
    }
}

private const val WSS_URL = "wss://chatgpt.test/backend-api/codex/responses"

/** What the Codex backend sent before each 1011 on 2026-09-25 (daemon log, 22:40Z to 23:51Z). */
private val CODEX_PREAMBLE = listOf(
    """{"type":"codex.rate_limits","plan_type":"pro"}""",
    """{"type":"codex.response.metadata","request_id":"req_1"}""",
)

private val COMPLETED_ROUND = listOf(
    """{"type":"response.created","response":{"id":"r1"}}""",
    """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[]}}""",
)

// The transport's own close line; the bypass line quotes the same words, so it is told apart by its start.
private const val CLOSE_LINE = "[ws] socket closed by the peer"
