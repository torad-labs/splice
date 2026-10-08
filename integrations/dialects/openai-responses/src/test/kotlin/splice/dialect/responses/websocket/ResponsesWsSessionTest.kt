// WALLS for the WS delta classifier (ws-transport WS-2/WS-4). Driven by the REAL
// ResponsesRequestBuilder over a growing conversation — a hand-written fixture would pin my
// ASSUMPTIONS about item shapes, and the whole risk here is that the real shapes differ.
//
// The failure modes these guard, in the order they would hurt:
//   wrong DROP  -> the server never sees an item; the model answers without context, silently
//   wrong SEND  -> the server sees an item twice; duplicated tool results, silently
//   wrong BAIL  -> a full send; costs bytes we already pay today (the acceptable failure)
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.request.responsesRequestJson

private val CODEX = ResponsesQuirks(providerTag = "claudex")
private const val KEY = "conv-1"
private const val GEN = 7L

/** Independent server-emitted item: the rebuilt client echo must match every stable field. */
private val SERVER_CALL_2: JsonObject = responsesRequestJson.parseToJsonElement(
    """{"type":"function_call","call_id":"call_2","name":"read","arguments":"{\"p\":\"2\"}"}""",
).jsonObject
private val CALL_2_EVIDENCE = WsServerEvidence(
    calls = mapOf("call_2" to SERVER_CALL_2),
    reasoning = mapOf("rs_env-call_2" to "env-call_2"),
)

private fun opts(effort: String? = null) = BuildOptions(
    compact = false,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    configEffort = effort,
    configSummary = null,
    showReasoning = ReasoningDisplay.TEXT,
    replayReasoning = InjectPriorReasoning(false),
    includeEncryptedReasoning = RequestEncryptedReasoning(true),
    decodeReasoningEnvelope = { data ->
        buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_$data"))
            put("encrypted_content", JsonPrimitive(data))
        }
    },
    reasoningLookup = { id -> listOf("env-$id") },
)

private fun build(json: String, effort: String? = null): JsonObject =
    AnthropicParse.parseAnthropicBody(json).let {
        ResponsesRequestBuilder(CODEX).build(it.typed, it.raw, opts(effort)).req
    }

/** A conversation after [rounds] completed tool round-trips, optionally with a trailing text turn. */
private fun convo(rounds: Int, trailingUserText: String? = null, assistantText: String? = null): String {
    val msgs = mutableListOf("""{"role":"user","content":"start"}""")
    for (i in 1..rounds) {
        val text = if (i == rounds && assistantText != null) {
            """{"type":"text","text":"$assistantText"},"""
        } else {
            ""
        }
        msgs += """{"role":"assistant","content":[$text{"type":"tool_use","id":"call_$i","name":"read","input":{"p":"$i"}}]}"""
        msgs += """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_$i","content":"out$i"}]}"""
    }
    if (trailingUserText != null) msgs += """{"role":"user","content":"$trailingUserText"}"""
    return """{"model":"m","messages":[${msgs.joinToString(",")}]}"""
}

private fun JsonObject.items(): List<JsonObject> = this["input"]!!.jsonArray.map { it.jsonObject }

private fun splice.dialect.responses.websocket.WsFrame.frameObj(): JsonObject =
    splice.dialect.responses.request.responsesRequestJson.parseToJsonElement(json) as JsonObject

private fun splice.dialect.responses.websocket.WsFrame.frameItems(): List<JsonObject> =
    frameObj()["input"]!!.jsonArray.map { it.jsonObject }

private fun typesOf(items: List<JsonObject>): List<String> =
    items.map { it["type"]?.jsonPrimitive?.content ?: "message:${it["role"]?.jsonPrimitive?.content}" }

class ResponsesWsSessionTest {

    /** Turn 1 always full-sends (no prior response to chain from) and carries the WS frame type. */
    @Test
    fun `first round is a full send and carries type response_create`() {
        val s = ResponsesWsSession()
        val req = build(convo(0, trailingUserText = null))
        val f = s.frameFor(KEY, req, GEN)
        assertFalse(f.chained)
        assertEquals("response.create", f.frameObj()["type"]?.jsonPrimitive?.content)
        assertEquals(req.items().size, f.frameItems().size, "full send carries the whole input")
        assertTrue(f.frameObj()["previous_response_id"] == null)
    }

    /** THE LEVERAGE: a tool round-trip chains, and the delta is EXACTLY the tool output — the
     *  rebuilt reasoning + function_call are dropped because the server produced them. */
    @Test
    fun `a tool round chains and sends only the function_call_output`() {
        val s = ResponsesWsSession()
        val r1 = build(convo(1))
        s.completed(KEY, r1, "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        val r2 = build(convo(2))
        val f = s.frameFor(KEY, r2, GEN)

        assertTrue(f.chained, "a pure tool continuation must chain")
        assertEquals("resp_1", f.frameObj()["previous_response_id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("function_call_output"),
            typesOf(f.frameItems()),
            "the server already holds the reasoning and the function_call it produced; re-sending duplicates",
        )
        assertTrue(
            f.frameItems().size < r2.items().size,
            "the whole point: ${f.frameItems().size} items on the wire instead of ${r2.items().size}",
        )
    }

    /** THE 2026-09-05 COMPACTION CLASS: the server's context ends with the tool call it just
     *  emitted, and Claude Code's auto-compaction fires BEFORE that call runs — its body ends at
     *  the previous tool result plus the compaction prompt, so no delta can answer the call. The
     *  server refused every chained one ("No tool output found for function call …") and the round
     *  fell back to a cold SSE send. It must full-send on this socket instead, and say why. */
    @Test
    fun `a turn that never answers the call the server holds full-sends and names the call`() {
        val s = ResponsesWsSession()
        s.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s.epochOf(KEY),
            pendingCalls = setOf("call_2"),
            evidence = CALL_2_EVIDENCE,
        )
        val f = s.frameFor(KEY, build(convo(1, trailingUserText = "summarize this session")), GEN)
        assertFalse(f.chained, "the delta would be [message] with call_2 unanswered — the server refuses that")
        assertTrue(f.frameObj()["previous_response_id"] == null)
        assertEquals(build(convo(1, trailingUserText = "summarize this session")).items().size, f.frameItems().size)
        assertTrue(f.fullSendReason?.contains("call_2") == true, "the reason names the call: ${f.fullSendReason}")
    }

    /** The same held call, ANSWERED: a normal tool round still chains, with or without a user
     *  message riding behind the tool output. */
    @Test
    fun `a turn that answers the held call chains as before`() {
        val s = ResponsesWsSession()
        s.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s.epochOf(KEY),
            pendingCalls = setOf("call_2"),
            evidence = CALL_2_EVIDENCE,
        )
        val plain = s.frameFor(KEY, build(convo(2)), GEN)
        assertTrue(plain.chained, "the delta answers call_2")
        assertEquals(listOf("function_call_output"), typesOf(plain.frameItems()))
        assertTrue(plain.fullSendReason == null)
        val s2 = ResponsesWsSession()
        s2.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s2.epochOf(KEY),
            pendingCalls = setOf("call_2"),
            evidence = CALL_2_EVIDENCE,
        )
        val steered = s2.frameFor(KEY, build(convo(2, trailingUserText = "and then this")), GEN)
        assertTrue(steered.chained, "an answered call plus a user message is the steering shape, still a chain")
        assertEquals(listOf("function_call_output", "message:user"), typesOf(steered.frameItems()))
    }

    /** The client's ECHO of the held call — the assistant tool_use rebuilt as a function_call with
     *  the same call_id — is not an answer: a body carrying it with no result still full-sends,
     *  and the reason line still names the call (review 2026-09-05: the reason read the echo as an
     *  answer and the full send went unlogged). */
    @Test
    fun `the echoed call itself is not an answer, so the reason still names it`() {
        val s = ResponsesWsSession()
        s.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s.epochOf(KEY),
            pendingCalls = setOf("call_2"),
            evidence = CALL_2_EVIDENCE,
        )
        val echoed = """{"model":"m","messages":[{"role":"user","content":"start"},""" +
            """{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"read","input":{"p":"1"}}]},""" +
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"out1"}]},""" +
            """{"role":"assistant","content":[{"type":"tool_use","id":"call_2","name":"read","input":{"p":"2"}}]},""" +
            """{"role":"user","content":"summarize this session"}]}"""
        val f = s.frameFor(KEY, build(echoed), GEN)
        assertFalse(f.chained, "call_2 has no output in this body")
        assertTrue(f.fullSendReason?.contains("call_2") == true, "the echo is not an answer: ${f.fullSendReason}")
    }

    /** A matching observed assistant TEXT block is already server-held and may be dropped. */
    @Test
    fun `a round whose assistant also spoke still chains when its text was observed`() {
        val s = ResponsesWsSession()
        s.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s.epochOf(KEY),
            evidence = CALL_2_EVIDENCE.copy(assistantTexts = listOf("thinking out loud")),
        )
        val f = s.frameFor(KEY, build(convo(2, assistantText = "thinking out loud")), GEN)
        assertTrue(f.chained)
        assertEquals(listOf("function_call_output"), typesOf(f.frameItems()))
    }

    @Test
    fun `streamed assistant item proves only its own text for same-key chaining`() {
        val session = ResponsesWsSession()
        val observer = ResponsesWsIdentity(session, LogSink { })
        val key = checkNotNull(ResponsesConversationIdentity.chainKey("same-session", "same-first-prompt"))
        val pending = ResponsesWsIdentity.PendingCommit(build(convo(1)), GEN, session.epochOf(key))
        val done = responsesRequestJson.parseToJsonElement(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant",""" +
                """"content":[{"type":"output_text","text":"A spoke"}]}}""",
        ).jsonObject
        val terminal = responsesRequestJson.parseToJsonElement(
            """{"type":"response.completed","response":{"id":"response-a","output":[]}}""",
        ).jsonObject
        val callDone = responsesRequestJson.parseToJsonElement(
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_2",""" +
                """"name":"read","arguments":"{\"p\":\"2\"}"}}""",
        ).jsonObject
        val reasoningDone = responsesRequestJson.parseToJsonElement(
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_env-call_2",""" +
                """"encrypted_content":"env-call_2"}}""",
        ).jsonObject
        val finalReasoning = responsesRequestJson.parseToJsonElement(
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_final",""" +
                """"encrypted_content":"final-cipher"}}""",
        ).jsonObject
        observer.observeTerminal(key, pending, reasoningDone)
        observer.observeTerminal(key, pending, done)
        observer.observeTerminal(key, pending, callDone)
        observer.observeTerminal(key, pending, finalReasoning)
        observer.observeTerminal(key, pending, terminal)
        assertTrue(session.frameFor(key, build(convo(2, assistantText = "A spoke")), GEN).chained)
        assertFalse(session.frameFor(key, build(convo(2, assistantText = "B spoke")), GEN).chained)
    }

    /** A user continuation sends the user message (client-new), not the history. */
    @Test
    fun `a user follow-up chains despite unreplayed final-answer reasoning`() {
        val s = ResponsesWsSession()
        s.completed(
            KEY,
            build(convo(1)),
            "resp_1",
            GEN,
            s.epochOf(KEY),
            evidence = WsServerEvidence(reasoning = mapOf("rs_final" to "cipher")),
        )
        val f = s.frameFor(KEY, build(convo(1, trailingUserText = "and now this")), GEN)
        assertTrue(f.chained)
        val types = typesOf(f.frameItems())
        assertEquals(1, types.size, "only the new user message rides: $types")
        assertTrue(types.single().startsWith("message"), "expected a user message, got $types")
    }

    /** BAIL: a reconnect means the server's per-connection context died — full send. */
    @Test
    fun `a new connection generation full-sends`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN + 1).chained)
    }

    /** BAIL: a pinned request property changed (codex gates connection reuse on exactly this). */
    @Test
    fun `an effort flip full-sends`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1), effort = "high"), "resp_1", GEN, s.epochOf(KEY))
        val f = s.frameFor(KEY, build(convo(2), effort = "low"), GEN)
        assertFalse(f.chained, "reasoning.effort is pinned per response; a change must not ride a chain")
    }

    /** BAIL: the prefix was rewritten (compaction, cache-key drift, an amended body). */
    @Test
    fun `a rewritten prefix full-sends`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(2)), "resp_1", GEN, s.epochOf(KEY))
        // A different opening message rewrites input[0] — everything after it is untrustworthy.
        val rewritten = build(
            """{"model":"m","messages":[{"role":"user","content":"DIFFERENT start"},""" +
                """{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"read","input":{"p":"1"}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"out1"}]}]}""",
        )
        assertFalse(s.frameFor(KEY, rewritten, GEN).chained)
    }

    /** BAIL: an identical re-send is a client retry, not a continuation — chaining it would ask
     *  the server to continue from a response with nothing new to react to. */
    @Test
    fun `an identical retry full-sends rather than chaining an empty delta`() {
        val s = ResponsesWsSession()
        val r = build(convo(2))
        s.completed(KEY, r, "resp_1", GEN, s.epochOf(KEY))
        assertFalse(s.frameFor(KEY, r, GEN).chained)
    }

    /** Guards the existing prefix-equality fallback when two same-key histories already differ. */
    @Test
    fun `rewritten same-key prefixes full-send in both directions`() {
        val session = ResponsesWsSession()
        val key = checkNotNull(ResponsesConversationIdentity.chainKey("same-session", "same-first-prompt"))
        val original = build(convo(1))
        val alternate = build(convo(1).replace("out1", "out2"))
        session.completed(key, original, "response-a", GEN, session.epochOf(key))
        val b = session.frameFor(key, alternate, GEN)
        assertFalse(b.chained, "the changed result rewrote a held prefix")
        assertEquals(null, b.frameObj()["previous_response_id"])
        session.completed(key, alternate, "response-b", GEN, session.epochOf(key))
        val a = session.frameFor(key, build(convo(1, trailingUserText = "continue")), GEN)
        assertFalse(a.chained, "A must not continue from B's committed response")
        assertEquals(null, a.frameObj()["previous_response_id"])
    }

    @Test
    fun `same-key fork cannot rewrite a server-held call under the same callback id`() {
        val session = ResponsesWsSession()
        val key = checkNotNull(ResponsesConversationIdentity.chainKey("same-session", "same-first-prompt"))
        session.completed(
            key,
            build(convo(1)),
            "response-a",
            GEN,
            session.epochOf(key),
            setOf("call_2"),
            evidence = CALL_2_EVIDENCE,
        )
        val changedCall = build(convo(2).replace("\"p\":\"2\"", "\"p\":\"different\""))
        val frame = session.frameFor(key, changedCall, GEN)
        assertFalse(frame.chained, "matching callback ids cannot prove the same arguments were issued")
        assertEquals(null, frame.frameObj()["previous_response_id"])
        val ordinary = build(convo(2))
        val items = ordinary.items().toMutableList()
        val callIndex = items.indexOfFirst { it["call_id"] == JsonPrimitive("call_2") }
        items[callIndex] = JsonObject(items[callIndex] - "arguments")
        val missingArgs = JsonObject(ordinary + ("input" to JsonArray(items)))
        assertFalse(
            session.frameFor(key, missingArgs, GEN).chained,
            "an omitted argument field is not proof of the server's call",
        )
    }

    @Test
    fun `same-key fork with unverified assistant text never chains onto another branch`() {
        val session = ResponsesWsSession()
        val key = checkNotNull(ResponsesConversationIdentity.chainKey("same-session", "same-first-prompt"))
        session.completed(
            key,
            build(convo(1)),
            "response-a",
            GEN,
            session.epochOf(key),
            setOf("call_2"),
            evidence = CALL_2_EVIDENCE.copy(assistantTexts = listOf("branch A said something else")),
        )
        val otherText = build(convo(2, assistantText = "branch B said something else"))
        val frame = session.frameFor(key, otherText, GEN)
        assertFalse(frame.chained, "a shared call id does not prove the server produced B's assistant text")
        assertEquals(null, frame.frameObj()["previous_response_id"])
    }

    @Test
    fun `a same-key fork cannot drop an unobserved tool search declaration`() {
        val session = ResponsesWsSession()
        val key = checkNotNull(ResponsesConversationIdentity.chainKey("same-session", "same-first-prompt"))
        val base = build(convo(1))
        session.completed(key, base, "response-a", GEN, session.epochOf(key))
        val search = buildJsonObject {
            put("type", JsonPrimitive("tool_search_call"))
            put("call_id", JsonPrimitive("shared-search-id"))
            put("execution", JsonPrimitive("client"))
            put("arguments", buildJsonObject { put("query", JsonPrimitive("branch B tool")) })
        }
        val result = buildJsonObject {
            put("type", JsonPrimitive("tool_search_output"))
            put("call_id", JsonPrimitive("shared-search-id"))
            put("output", JsonPrimitive("B's schema"))
        }
        val newMessage = build(convo(1, trailingUserText = "next")).items().last()
        val suffix = JsonObject(base + ("input" to JsonArray(base.items() + search + result + newMessage)))
        assertFalse(
            session.frameFor(key, suffix, GEN).chained,
            "the server never observed B's generated declaration, so dropping it loses context",
        )
    }

    /** BAIL: an unmodelled item shape anywhere in the suffix. Wrong-drop and wrong-send are both
     *  silent corruption; a full send is merely the status quo. */
    @Test
    fun `an unknown item type in the suffix full-sends`() {
        val s = ResponsesWsSession()
        val base = build(convo(1))
        s.completed(KEY, base, "resp_1", GEN, s.epochOf(KEY))
        val withAlien = JsonObject(
            base.toMutableMap().apply {
                put(
                    "input",
                    JsonArray(base.items() + buildJsonObject { put("type", JsonPrimitive("image_generation_call")) }),
                )
            },
        )
        assertFalse(s.frameFor(KEY, withAlien, GEN).chained)
    }

    /** THE EPOCH FENCE (review of #72). Clearing alone cannot fix an ORDERING problem: a bypass
     *  clears while a WS round is still in flight, and that round's terminal lands afterwards. Its
     *  commit must be discarded, or the next turn chains onto context the server never got. */
    @Test
    fun `a commit built before an invalidation is discarded, not resurrected`() {
        val s = ResponsesWsSession()
        val r1 = build(convo(1))
        val epochAtSend = s.epochOf(KEY) // the in-flight round captures this...
        s.cleared(KEY) // ...then something bypasses (busy round rides SSE)
        s.completed(KEY, r1, "resp_1", GEN, epochAtSend, evidence = CALL_2_EVIDENCE) // ...and the terminal lands LATE
        assertFalse(
            s.frameFor(KEY, build(convo(2)), GEN).chained,
            "a stale-epoch commit must NOT resurrect the chain — the next round full-sends",
        )
    }

    /** DR-78 (fresh-eyes sweep): the fence must be PER CONVERSATION. A never-cleared key's epoch
     *  used to fall back to the GLOBAL seq at capture AND commit, so any other conversation's
     *  clear bumped seq mid-flight and voided this key's commit — on a busy daemon every tear
     *  anywhere silently defeated chaining for every concurrent conversation. */
    @Test
    fun `another conversation's clear does not void an in-flight commit - DR-78`() {
        val s = ResponsesWsSession()
        val r1 = build(convo(1))
        val epochAtSend = s.epochOf(KEY) // conversation A's round captures its epoch...
        s.cleared("conv-unrelated") // ...an UNRELATED conversation tears mid-flight...
        s.completed(KEY, r1, "resp_1", GEN, epochAtSend, evidence = CALL_2_EVIDENCE) // ...and A's clean terminal lands
        assertTrue(
            s.frameFor(KEY, build(convo(2)), GEN).chained,
            "A's commit survives B's clear — the fence is per conversation",
        )
    }

    /** The fence must not block the normal path: a commit under the CURRENT epoch still applies. */
    @Test
    fun `a commit under the current epoch still chains`() {
        val s = ResponsesWsSession()
        s.cleared(KEY) // epoch moves; a round STARTED AFTER it captures the new value
        val r1 = build(convo(1))
        s.completed(KEY, r1, "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        assertTrue(s.frameFor(KEY, build(convo(2)), GEN).chained)
    }

    /** State is committed only on a clean terminal; a tear/cancel must not leave a chain behind. */
    @Test
    fun `a cleared conversation full-sends, and a null response id never commits`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        s.cleared(KEY)
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN).chained)

        s.completed(KEY, build(convo(1)), null, GEN, s.epochOf(KEY))
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN).chained, "a terminal without an id is not chainable")
    }

    /** Chaining is per-conversation: one conversation's state never serves another. */
    @Test
    fun `chains are scoped per conversation key`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        assertFalse(s.frameFor("other-conv", build(convo(2)), GEN).chained)
    }

    @Test
    fun `a chain larger than the total byte cap is evicted to full-send status quo`() {
        val s = ResponsesWsSession(maxTotalBytes = 1)
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)

        assertFalse(
            s.frameFor(KEY, build(convo(2)), GEN).chained,
            "an over-cap chain must be forgotten rather than pinning its full history",
        )
    }

    @Test
    fun `count pressure evicts the least recently used chain`() {
        val s = ResponsesWsSession(maxConversations = 1)
        s.completed("old", build(convo(1)), "resp_old", GEN, s.epochOf("old"))
        s.completed("new", build(convo(1)), "resp_new", GEN, s.epochOf("new"), evidence = CALL_2_EVIDENCE)

        assertFalse(s.frameFor("old", build(convo(2)), GEN).chained)
        assertTrue(s.frameFor("new", build(convo(2)), GEN).chained)
    }

    @Test
    fun `an idle chain expires wholesale`() {
        var now = 0L
        val s = ResponsesWsSession(ttlMs = 10, clock = ElapsedClock { now })
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        now = 11

        assertFalse(
            s.frameFor(KEY, build(convo(2)), GEN).chained,
            "an idle chain must degrade to one full send after its TTL",
        )
    }

    @Test
    fun `building a chained frame refreshes the idle TTL`() {
        var now = 0L
        val s = ResponsesWsSession(ttlMs = 10, clock = ElapsedClock { now })
        s.completed(KEY, build(convo(1)), "resp_1", GEN, s.epochOf(KEY), evidence = CALL_2_EVIDENCE)
        now = 9
        assertTrue(s.frameFor(KEY, build(convo(2)), GEN).chained)
        now = 18

        assertTrue(
            s.frameFor(KEY, build(convo(2)), GEN).chained,
            "frame construction at t=9 must keep the chain alive through t=18",
        )
    }

    /** The full-send frame must be byte-identical to the request plus the WS envelope keys —
     *  no field invented, none lost (the closed-DTO law this repo already holds elsewhere). */
    @Test
    fun `a full send preserves every request field exactly`() {
        val s = ResponsesWsSession()
        val req = build(convo(2))
        val frame = s.frameFor(KEY, req, GEN).frameObj()
        assertEquals(req.keys + "type", frame.keys)
        req.forEach { (k, v) -> assertEquals(v, frame[k], "field $k must ride unchanged") }
    }
}
