// WALLS for the Responses side of the WS seam (review of #72). Three classes of finding are
// pinned here, each one a silent-corruption path rather than an error:
//
//  * THE TERMINAL VOCABULARY. Six event names decide whether a round completes, hangs, or falls
//    back. A typo in any of them is invisible to every other test, so all six are parameterized.
//  * THE CHAIN CLEARS ON FAILURE. A chain committed from a round that did not cleanly finish
//    would anchor the next turn onto context the server never built.
//  * THE ISOLATION KEY. Without a session id there is no safe chain identity, and substituting an
//    empty string re-opens the cross-conversation collision the two-part key exists to close.
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplayParser
import splice.core.turn.TurnMeta
import splice.dialect.responses.ResponsesRoundEnd
import splice.dialect.responses.ResponsesWsRunner
import splice.dialect.responses.ResponsesWsSession
import splice.dialect.responses.WsUpstream
import splice.dialect.responses.responsesRequestJson
import java.io.IOException
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture

private const val BODY = """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"}]}"""

private fun meta(session: String? = "sess-1", conversation: String? = "splice-abc") = TurnMeta(
    compact = false,
    showReasoning = ReasoningDisplayParser.from("text"),
    stream = true,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    clientMaxTokens = null,
    effort = "high",
    summary = "detailed",
    budgetTokens = null,
    conversationKey = conversation,
    sessionId = session,
)

/** A scripted socket: every send replies with the frames the script returns for that round. */
private class Rig(private val script: (Int) -> List<String>) {
    var rounds = 0
    val sent = mutableListOf<String>()

    /** Which SOCKETS were aborted, in creation order. kill() calls abort(), so this is how a test
     *  observes "the round's connection was torn down" and, more importantly, WHICH one. */
    val aborted = mutableListOf<Int>()
    private var sockets = 0
    private val transport = WsUpstream(connector = ::connect)
    val session = ResponsesWsSession()
    val runner = ResponsesWsRunner(
        transport = transport,
        session = session,
        wssUrl = "wss://example.invalid/responses",
        handshakeHeaders = { emptyMap() },
    )

    private var listener: WebSocket.Listener? = null

    @Suppress("UNUSED_PARAMETER")
    private fun connect(unusedUri: URI, unusedHeaders: Map<String, String>, l: WebSocket.Listener): WebSocket {
        listener = l
        // Its OWN listener, not the shared field: a rig with two live sockets would otherwise feed
        // every frame to whichever connected last.
        val index = sockets++
        val socket = object : WebSocket {
            override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
                sent += data.toString()
                val frames = script(rounds++)
                frames.forEach { l.onText(this, it, true) }
                return CompletableFuture.completedFuture(this)
            }
            override fun sendBinary(
                d: java.nio.ByteBuffer,
                l2: Boolean,
            ) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendPing(m: java.nio.ByteBuffer) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendPong(m: java.nio.ByteBuffer) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendClose(c: Int, r: String) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun request(n: Long) = Unit
            override fun getSubprotocol() = ""
            override fun isOutputClosed() = false
            override fun isInputClosed() = false
            override fun abort() {
                aborted += index
            }
        }
        l.onOpen(socket)
        return socket
    }

    suspend fun accept(m: TurnMeta = meta(), body: String = BODY, headers: Map<String, String> = emptyMap()) =
        runner.attempt(body, m, headers, Credentials.Bearer("tok", "acct"))

    suspend fun round(m: TurnMeta = meta(), body: String = BODY): List<JsonObject>? =
        accept(m, body)?.let { r -> mutableListOf<JsonObject>().also { out -> r.events.collect { out += it } } }

    fun lastSentChained(): Boolean =
        (responsesRequestJson.parseToJsonElement(sent.last()) as JsonObject)["previous_response_id"] != null
}

private fun completed(id: String) = """{"type":"response.completed","response":{"id":"$id"}}"""

class ResponsesWsRunnerTest {

    /** Every SUCCESS variant must END the round — otherwise the flow never completes, the
     *  connection never returns to the pool, and the turn HANGS (worse than any error). */
    @ParameterizedTest
    @ValueSource(strings = ["response.completed", "response.done", "response.incomplete"])
    fun `each success terminal completes the round`(type: String) = runTest {
        val rig = Rig { listOf("""{"type":"response.created"}""", """{"type":"$type","response":{"id":"r1"}}""") }
        val seen = rig.round()
        assertNotNull(seen, "$type must be recognised as a terminal")
        assertEquals(listOf("response.created", type), seen!!.map { it["type"]!!.toString().trim('"') })
    }

    /** Every FAILED variant must be recognised as a failure terminal — that predicate is what lets
     *  the head bail to SSE while the client has seen nothing, keeping retry/refresh/cooldown. */
    @ParameterizedTest
    @ValueSource(strings = ["response.failed", "response.error", "error"])
    fun `each failure terminal is recognised as a failure`(type: String) = runTest {
        val rig = Rig { listOf("""{"type":"$type"}""") }
        val event = responsesRequestJson.parseToJsonElement("""{"type":"$type"}""") as JsonObject
        assertTrue(rig.runner.isFailureTerminal(event), "$type must be a FAILURE terminal, not a success")
    }

    /** The vocabulary itself: six names, disjoint, and every one of them ends a round. */
    @Test
    fun `the terminal sets are disjoint and jointly end every round`() {
        assertTrue(ResponsesRoundEnd.SUCCESS.intersect(ResponsesRoundEnd.FAILED).isEmpty())
        assertEquals(ResponsesRoundEnd.SUCCESS + ResponsesRoundEnd.FAILED, ResponsesRoundEnd.ALL)
        assertEquals(6, ResponsesRoundEnd.ALL.size)
    }

    /** A clean terminal commits the chain, so the NEXT round is a delta. */
    @Test
    fun `a clean terminal commits the chain and the next round chains`() = runTest {
        val rig = Rig { i -> listOf(completed("resp_$i")) }
        rig.round()
        assertFalse(rig.lastSentChained(), "the first round has nothing to chain onto")
        val grown =
            """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"},{"role":"user","content":"more"}]}"""
        rig.round(body = grown)
        assertTrue(rig.lastSentChained(), "a committed chain must produce a previous_response_id delta")
    }

    /** THE REVIEW'S CONCERN: a FAILURE terminal must clear the chain, so the next attempt sends a
     *  FULL request. Without this a future terminal-handling change could leave an incomplete chain
     *  committed while the success-oriented tests above stayed green. */
    @ParameterizedTest
    @ValueSource(strings = ["response.failed", "response.error", "error"])
    fun `a failure terminal clears the chain and the next round full-sends`(type: String) = runTest {
        val rig = Rig { i -> if (i == 0) listOf(completed("resp_0")) else listOf("""{"type":"$type"}""") }
        rig.round() // commits resp_0
        val grown = """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"},{"role":"user","content":"b"}]}"""
        rig.round(body = grown) // ends in $type -> must CLEAR
        val grownMore =
            """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"},{"role":"user","content":"b"},""" +
                """{"role":"user","content":"c"}]}"""
        rig.round(body = grownMore)
        assertFalse(rig.lastSentChained(), "after a $type terminal the next round must FULL-send")
    }

    /** A round the overlay did not serve still advanced the conversation. */
    @Test
    fun `roundBypassed clears the chain`() = runTest {
        val rig = Rig { i -> listOf(completed("resp_$i")) }
        rig.round()
        rig.runner.roundBypassed(meta())
        val grown = """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"},{"role":"user","content":"b"}]}"""
        rig.round(body = grown)
        assertFalse(rig.lastSentChained(), "an SSE-served turn is invisible to the server chain; do not chain over it")
    }

    /** THE ISOLATION BLOCKER: no session id means NO safe chain identity. Substituting an empty
     *  string would fuse every conversation whose first message hashes the same. */
    @Test
    fun `a missing session id refuses the ws path entirely`() = runTest {
        val rig = Rig { i -> listOf(completed("resp_$i")) }
        assertNull(rig.round(meta(session = null)), "no session id => ride SSE, never chain on a fusable key")
        assertNull(rig.round(meta(session = "")), "an empty session id is absent, not a value")
        assertNull(rig.round(meta(conversation = null)), "no conversation key => same refusal")
        assertEquals(0, rig.rounds, "not one frame may reach the wire without an isolation identity")
    }

    /** DR-7: the abort kills THIS round's socket and its events end as an IOException — the shape
     *  the head depends on, because a torn read is what the translator folds into an honest
     *  terminal. A cancellation instead would take the collector down and lose the salvage. */
    @Test
    fun `aborting a live round tears its own socket and ends the flow as a torn read - DR-7`() = runTest {
        val rig = Rig { listOf(created("resp_1")) }
        val round = checkNotNull(rig.accept()) { "the scripted round must be accepted" }

        round.abort.abort()

        assertEquals(listOf(0), rig.aborted, "the round's own socket must be torn down")
        assertThrows(IOException::class.java) {
            runBlocking { round.events.collect { } }
        }
    }

    /** DR-7, THE IDENTITY HOLE the first draft had (grok-splice, before it shipped). The chaining
     *  identity is (session, conversation), but a CONNECTION is keyed by that plus the model and a
     *  digest of the per-turn headers — on purpose, because a compact turn must not reuse a socket
     *  opened with the lite marker. So ONE conversation can hold two live rounds on two sockets,
     *  and an abort looked up by chain would tear down whichever registered last. The abort rides
     *  the round instead, so it cannot reach a sibling. */
    @Test
    fun `aborting one round of a conversation never touches its sibling on another socket - DR-7`() = runTest {
        val rig = Rig { listOf(created("resp_1")) }
        val first = checkNotNull(rig.accept(headers = mapOf("x-splice-probe" to "one"))) { "round one" }
        val second = checkNotNull(rig.accept(headers = mapOf("x-splice-probe" to "two"))) { "round two" }

        first.abort.abort()

        assertEquals(listOf(0), rig.aborted, "only the aborted round's socket may be torn")
        second.abort.abort()
        assertEquals(listOf(0, 1), rig.aborted, "and the sibling's abort still reaches its own")
    }

    /** DR-7, THE REUSE HOLE, and the reason the guard is a LEASE and not terminalSeen. A finished
     *  round returns its connection to the pool; the next round acquires the SAME object, and
     *  acquire RESETS terminalSeen to false. A stale abort gated on that flag would look at the
     *  reused connection, see "no terminal yet", and kill the round that had just taken it over —
     *  the cross-turn tear, bought while closing the harmless idle-pool case. The lease is bumped
     *  by acquire, so a stale abort simply does not match. */
    @Test
    fun `an abort from a finished round cannot kill the round that reused its connection - DR-7`() = runTest {
        val rig = Rig { i -> if (i == 0) listOf(completed("resp_1")) else listOf(created("resp_2")) }
        val finished = checkNotNull(rig.accept()) { "the first round must be accepted" }
        finished.events.collect { }
        val reusing = checkNotNull(rig.accept()) { "the reusing round must be accepted" }

        finished.abort.abort()

        assertTrue(rig.aborted.isEmpty(), "a stale abort must not tear the connection its successor now holds")
        reusing.abort.abort()
        assertEquals(listOf(0), rig.aborted, "the round that actually holds it can still abort it")
    }
}

private fun created(id: String) = """{"type":"response.created","response":{"id":"$id"}}"""
