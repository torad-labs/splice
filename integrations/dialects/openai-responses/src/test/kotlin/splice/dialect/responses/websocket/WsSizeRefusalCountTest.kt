// NEW: the peer refusing a round's frame as too large is counted on the turn, so its SSE ride can read the 400 after it.
package splice.dialect.responses.websocket

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import java.io.IOException
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

private const val MESSAGE_TOO_BIG = 1009
private const val INTERNAL_ERROR = 1011
private const val FRAME = """{"model":"gpt-6.1-sol","input":[{"role":"user","content":"hi"}]}"""
private const val CREATED = """{"type":"response.created","response":{"id":"resp_1"}}"""

/**
 * Oct 4, 6:31 AM CT: the Responses peer closed 42 rounds' sockets with 1009 before their first event, in two
 * shapes the log shows: the close lands after the send is accepted ("inbox closed before first event"), or
 * during it, so the send future fails ("send failed async (IOException: Output closed)"). Both ride SSE, and the
 * HTTP 400 that answers the same body there is only readable as a size refusal if this attempt said so.
 */
class WsSizeRefusalCountTest {
    private class Closing(
        private val code: Int,
        private val eventsFirst: List<String> = emptyList(),
        private val failSend: Boolean = false,
    ) {
        val runner = ResponsesWsRunner(
            transport = WsUpstream(connector = { _, _, listener -> socket(listener) }),
            session = ResponsesWsSession(),
            wssUrl = "wss://example.invalid/responses",
            handshakeHeaders = { emptyMap() },
        )

        private fun socket(listener: WebSocket.Listener): WebSocket = object : WebSocket {
            override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
                eventsFirst.forEach { listener.onText(this, it, true) }
                listener.onClose(this, code, "")
                return if (failSend) CompletableFuture.failedFuture(IOException("Output closed")) else done()
            }

            override fun sendBinary(data: ByteBuffer, last: Boolean) = done()
            override fun sendPing(message: ByteBuffer) = done()
            override fun sendPong(message: ByteBuffer) = done()
            override fun sendClose(statusCode: Int, reason: String) = done()
            override fun request(n: Long) = Unit
            override fun getSubprotocol() = ""
            override fun isOutputClosed() = false
            override fun isInputClosed() = false
            override fun abort() = Unit
            private fun done() = CompletableFuture.completedFuture<WebSocket>(this)
        }.also { listener.onOpen(it) }

        suspend fun attempt(perf: TurnPerf) =
            runner.attempt(FRAME, turnMeta(), emptyMap(), Credentials.Bearer("tok", "acct"), perf)

        private fun turnMeta() = TurnMeta(
            compact = false,
            reasoning = TurnReasoning(
                showReasoning = ReasoningDisplay.TEXT,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            route = TurnRoute(
                stream = true,
                originalModel = "claudex--gpt-6.1-sol",
                upstreamModel = "gpt-6.1-sol",
                clientMaxTokens = null,
            ),
            scope = TurnScope(conversationKey = "splice-size", sessionId = "sess-size"),
        )
    }

    private fun refusals(perf: TurnPerf): Long? = perf.snapshot().counters[PerfKeys.WS_REFUSED_TOO_LARGE]

    @Test
    fun `a 1009 before the first event refuses the frame and the round rides SSE`() = runTest {
        val perf = TurnPerf { 0L }
        assertNull(Closing(MESSAGE_TOO_BIG).attempt(perf))
        assertEquals(1L, refusals(perf))
    }

    @Test
    fun `a 1009 that fails the send itself is the same refusal`() = runTest {
        val perf = TurnPerf { 0L }
        assertNull(Closing(MESSAGE_TOO_BIG, failSend = true).attempt(perf))
        assertEquals(1L, refusals(perf))
    }

    @Test
    fun `any other close before the first event says nothing about the frame's size`() = runTest {
        val perf = TurnPerf { 0L }
        assertNull(Closing(INTERNAL_ERROR).attempt(perf))
        assertNull(refusals(perf))
    }

    @Test
    fun `a 1009 after the round's first event is a torn round, not a refused frame`() = runTest {
        val perf = TurnPerf { 0L }
        val round = checkNotNull(Closing(MESSAGE_TOO_BIG, eventsFirst = listOf(CREATED)).attempt(perf))
        val torn = runCatching { round.events.toList() }.exceptionOrNull()
        assertTrue(torn is IOException, "the round tears: $torn")
        assertNull(refusals(perf))
    }
}
