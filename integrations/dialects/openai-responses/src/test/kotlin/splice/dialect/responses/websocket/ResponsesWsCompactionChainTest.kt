// Goal (Marcos, 2026-10-25, "both"): Claude Code sends a compaction as the previous request plus the summary
// instruction and drops the answer it just received, so that answer's tool call stays open on the Responses
// websocket. Full-sent, the compaction depends on the provider finding the prompt again, and on claudex it missed
// the cache far more often than a chained turn (0 of 14 chained compactions missed; 17 of 228 warm ones did).
// So a COMPACTION that leaves the answer's calls open chains onto that answer: each open call is closed with the
// output codex-rs itself writes for a call that has none ("aborted", context_manager/normalize.rs
// ensure_call_outputs_present), then the compaction's new items follow. A turn that is not a compaction keeps
// today's full send, and a compaction that rewrote its history still full-sends.
package splice.dialect.responses.websocket

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.dialect.responses.request.responsesRequestJson
import splice.dialect.responses.websocket.ResponsesWsIdentity.PendingCommit
import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

private fun turn(compact: Boolean) = TurnMeta(
    compact = compact,
    reasoning = TurnReasoning(ReasoningDisplay.TEXT, effort = "xhigh", summary = "detailed", budgetTokens = null),
    route = TurnRoute(
        stream = true,
        originalModel = "claude-codex--gpt-6.1-sol",
        upstreamModel = "gpt-6.1-sol",
        clientMaxTokens = null,
    ),
    scope = TurnScope(conversationKey = "splice-conv", sessionId = "sess-compact"),
)

private fun item(vararg pairs: Pair<String, String>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

private val HI = item("role" to "user", "content" to "hi")
private val SUMMARIZE = item("role" to "user", "content" to "CRITICAL: Respond with TEXT ONLY. Summarize.")

private fun body(items: List<JsonObject>): String = JsonWire.string(
    buildJsonObject {
        put("model", JsonPrimitive("gpt-6.1-sol"))
        put("input", JsonArray(items))
    },
)

private fun aborted(type: String, callId: String) = item("type" to type, "call_id" to callId, "output" to "aborted")

private fun itemDone(item: JsonObject) =
    """{"type":"response.output_item.done","output_index":0,"item":${JsonWire.string(item)}}"""

private const val CREATED = """{"type":"response.created"}"""

private fun completed(id: String) = """{"type":"response.completed","response":{"id":"$id","output":[]}}"""

/** The real runner and websocket transport over a scripted socket: each send answers with that round's frames. */
private class CompactionRig(private val script: (Int) -> List<String>) {
    val sent = mutableListOf<JsonObject>()
    val logs = mutableListOf<String>()
    private var rounds = 0
    val runner = ResponsesWsRunner(
        transport = WsUpstream(connector = ::connect),
        session = ResponsesWsSession(),
        wssUrl = "wss://example.invalid/responses",
        handshakeHeaders = { emptyMap() },
        webSocketPrefill = false,
        log = LogSink { logs += it },
    )

    private fun connect(uri: URI, headers: Map<String, String>, listener: WebSocket.Listener): WebSocket {
        check(uri.toString().isNotEmpty() && headers.size >= 0)
        val socket = object : WebSocket {
            override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
                sent += responsesRequestJson.parseToJsonElement(data.toString()).jsonObject
                script(rounds++).forEach { listener.onText(this, it, true) }
                return CompletableFuture.completedFuture(this)
            }
            override fun sendBinary(d: ByteBuffer, l: Boolean) = done()
            override fun sendPing(m: ByteBuffer) = done()
            override fun sendPong(m: ByteBuffer) = done()
            override fun sendClose(c: Int, r: String) = done()
            private fun done() = CompletableFuture.completedFuture<WebSocket>(this)
            override fun request(n: Long) = Unit
            override fun getSubprotocol() = ""
            override fun isOutputClosed() = false
            override fun isInputClosed() = false
            override fun abort() = Unit
        }
        listener.onOpen(socket)
        return socket
    }

    suspend fun round(items: List<JsonObject>, compact: Boolean = false) {
        val round = checkNotNull(runner.attempt(body(items), turn(compact), emptyMap(), Credentials.Bearer("t", "a")))
        round.events.collect { }
    }

    fun lastPrevious(): String? = sent.last()["previous_response_id"]?.jsonPrimitive?.content

    fun lastInput(): List<JsonObject> = sent.last()["input"]!!.jsonArray.map { it.jsonObject }
}

class ResponsesWsCompactionChainTest {
    @Test
    fun `a compaction that leaves the answer's function call open chains onto that answer with the call aborted`() =
        runTest {
            val call = item("type" to "function_call", "call_id" to "call_9", "name" to "read", "arguments" to "{}")
            val rig = CompactionRig { round ->
                if (round == 0) listOf(CREATED, itemDone(call), completed("r1")) else listOf(CREATED, completed("r2"))
            }
            rig.round(listOf(HI))
            rig.round(listOf(HI, SUMMARIZE), compact = true)

            assertEquals("r1", rig.lastPrevious(), "the compaction continues the answer it dropped")
            assertEquals(listOf(aborted("function_call_output", "call_9"), SUMMARIZE), rig.lastInput())
            assertTrue(rig.logs.any { "chained onto the previous response" in it }, rig.logs.toString())
        }

    /** The live claudex shape (10:36 CT, Oct 25): the answer was reasoning plus code mode's exec call, and the
     *  compaction body carries that reasoning back before the instruction. The echo is the server's own item,
     *  so it is not re-sent; the exec call is closed with code mode's custom output. */
    @Test
    fun `the live code-mode compaction chains with its exec call aborted and its echoed reasoning not re-sent`() =
        runTest {
            val reasoning = item("type" to "reasoning", "id" to "rs_1", "encrypted_content" to "enc1")
            val exec = item(
                "type" to "custom_tool_call",
                "id" to "ctc_1",
                "call_id" to "call_1",
                "name" to "exec",
                "input" to "await tools.Read({file_path: '/f'})",
                "status" to "completed",
            )
            val rig = CompactionRig { round ->
                if (round == 0) {
                    listOf(CREATED, itemDone(reasoning), itemDone(exec), completed("r1"))
                } else {
                    listOf(CREATED, completed("r2"))
                }
            }
            rig.round(listOf(HI))
            rig.round(listOf(HI, reasoning, SUMMARIZE), compact = true)

            assertEquals("r1", rig.lastPrevious())
            assertEquals(listOf(aborted("custom_tool_call_output", "call_1"), SUMMARIZE), rig.lastInput())
        }

    @Test
    fun `a turn that is not a compaction still full-sends when it leaves the call open`() = runTest {
        val call = item("type" to "function_call", "call_id" to "call_9", "name" to "read", "arguments" to "{}")
        val rig = CompactionRig { round ->
            if (round == 0) listOf(CREATED, itemDone(call), completed("r1")) else listOf(CREATED, completed("r2"))
        }
        rig.round(listOf(HI))
        rig.round(listOf(HI, SUMMARIZE), compact = false)

        assertNull(rig.lastPrevious(), "only a compaction may close a call the client never answered")
        assertEquals(listOf(HI, SUMMARIZE), rig.lastInput())
    }

    @Test
    fun `a compaction that rewrote its history still full-sends`() = runTest {
        val call = item("type" to "function_call", "call_id" to "call_9", "name" to "read", "arguments" to "{}")
        val rig = CompactionRig { round ->
            if (round == 0) listOf(CREATED, itemDone(call), completed("r1")) else listOf(CREATED, completed("r2"))
        }
        rig.round(listOf(HI))
        val rewritten = item("role" to "user", "content" to "hi, edited")
        rig.round(listOf(rewritten, SUMMARIZE), compact = true)

        assertNull(rig.lastPrevious(), "the server never held this history")
        assertEquals(listOf(rewritten, SUMMARIZE), rig.lastInput())
    }

    /** The closed call exists only on the server. A later request that extends the compaction's own body, here
     *  with the summary the server really answered, does not show that closure, so it full-sends rather than
     *  chain onto context its input does not hold. */
    @Test
    fun `a request extending a closed compaction full-sends`() = runTest {
        val call = item("type" to "function_call", "call_id" to "call_9", "name" to "read", "arguments" to "{}")
        val summary = item("role" to "assistant", "content" to "the summary")
        val rig = CompactionRig { round ->
            when (round) {
                0 -> listOf(CREATED, itemDone(call), completed("r1"))
                1 -> listOf(CREATED, itemDone(JsonObject(item("type" to "message") + summary)), completed("r2"))
                else -> listOf(CREATED, completed("r${round + 1}"))
            }
        }
        rig.round(listOf(HI))
        rig.round(listOf(HI, SUMMARIZE), compact = true)
        rig.round(listOf(HI, SUMMARIZE, summary, item("role" to "user", "content" to "go on")))

        assertNull(rig.lastPrevious())
    }

    /** Over generated answers: one to three open calls of either kind, reasoning before each, the client echoing
     *  all, some or none of that reasoning. A compaction always chains onto the answer, closes every open call in
     *  the order the server emitted them with the output its kind takes, then sends only the instruction; the same
     *  body as an ordinary turn full-sends. */
    @Test
    fun `every compaction over a generated open answer closes each call in order and sends only its new items`() {
        repeat(GENERATED_CASES) { seed ->
            val random = Random(seed)
            val calls = (1..random.nextInt(1, MAX_CALLS + 1)).map { n -> random.nextBoolean() to "call_$n" }
            val reasoning = calls.indices.map { n ->
                item("type" to "reasoning", "id" to "rs_$n", "encrypted_content" to "e$n")
            }
            val callItems = calls.map { (custom, id) -> if (custom) execCall(id) else functionCall(id) }
            val session = ResponsesWsSession()
            val first = request(listOf(HI))
            session.completed(
                KEY,
                PendingCommit(first, GENERATION, session.epochOf(KEY)),
                "r1",
                pendingCalls = calls.map { it.second }.toSet(),
                evidence = WsServerEvidence(
                    calls = callItems.associateBy { it["call_id"]!!.jsonPrimitive.content },
                    reasoning = reasoning.associate { r ->
                        r["id"]!!.jsonPrimitive.content to r["encrypted_content"]!!.jsonPrimitive.content
                    },
                ),
            )
            val echoed = reasoning.take(random.nextInt(0, reasoning.size + 1))
            val compaction = request(listOf(HI) + echoed + SUMMARIZE)

            val plain = session.frameFor(KEY, compaction, GENERATION)
            val frame = session.frameFor(KEY, compaction, GENERATION, compaction = true)

            val expected = calls.map { (custom, id) ->
                aborted(if (custom) "custom_tool_call_output" else "function_call_output", id)
            } + SUMMARIZE
            val sent = responsesRequestJson.parseToJsonElement(frame.json).jsonObject
            assertEquals(false, plain.chained, "seed $seed: an ordinary turn keeps the full send")
            assertEquals(true, frame.chained, "seed $seed")
            assertEquals("r1", sent["previous_response_id"]?.jsonPrimitive?.content, "seed $seed")
            assertEquals(expected, sent["input"]!!.jsonArray.map { it.jsonObject }, "seed $seed")
        }
    }

    private fun request(items: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", JsonPrimitive("gpt-6.1-sol"))
        put("input", JsonArray(items))
    }

    private fun functionCall(id: String) =
        item("type" to "function_call", "call_id" to id, "name" to "read", "arguments" to "{}")

    private fun execCall(id: String) =
        item("type" to "custom_tool_call", "call_id" to id, "name" to "exec", "input" to "await tools.Read({})")
}

private const val KEY = "conv-compact"
private const val GENERATION = 7L
private const val GENERATED_CASES = 200
private const val MAX_CALLS = 3
