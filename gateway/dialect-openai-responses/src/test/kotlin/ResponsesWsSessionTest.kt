// WALLS for the WS delta classifier (ws-transport WS-2/WS-4). Driven by the REAL
// ResponsesRequestBuilder over a growing conversation — a hand-written fixture would pin my
// ASSUMPTIONS about item shapes, and the whole risk here is that the real shapes differ.
//
// The failure modes these guard, in the order they would hurt:
//   wrong DROP  -> the server never sees an item; the model answers without context, silently
//   wrong SEND  -> the server sees an item twice; duplicated tool results, silently
//   wrong BAIL  -> a full send; costs bytes we already pay today (the acceptable failure)
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
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ResponsesWsSession

private val CODEX = ResponsesQuirks(providerTag = "claudex")
private const val KEY = "conv-1"
private const val GEN = 7L

private fun opts(effort: String? = null) = BuildOptions(
    compact = false,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    configEffort = effort,
    configSummary = null,
    showReasoning = ReasoningDisplay.from("text"),
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
    parseAnthropicBody(json).let { ResponsesRequestBuilder(CODEX).build(it.typed, it.raw, opts(effort)).req }

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

private fun splice.dialect.responses.WsFrame.frameObj(): JsonObject =
    splice.dialect.responses.responsesRequestJson.parseToJsonElement(json) as JsonObject

private fun splice.dialect.responses.WsFrame.frameItems(): List<JsonObject> =
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
        s.completed(KEY, r1, "resp_1", GEN)
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

    /** An assistant TEXT block in the suffix is server-held too (the model produced it). */
    @Test
    fun `a round whose assistant also spoke still chains, dropping the assistant message`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN)
        val f = s.frameFor(KEY, build(convo(2, assistantText = "thinking out loud")), GEN)
        assertTrue(f.chained)
        assertEquals(listOf("function_call_output"), typesOf(f.frameItems()))
    }

    /** A user continuation sends the user message (client-new), not the history. */
    @Test
    fun `a user follow-up chains and sends the user message`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN)
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
        s.completed(KEY, build(convo(1)), "resp_1", GEN)
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN + 1).chained)
    }

    /** BAIL: a pinned request property changed (codex gates connection reuse on exactly this). */
    @Test
    fun `an effort flip full-sends`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1), effort = "high"), "resp_1", GEN)
        val f = s.frameFor(KEY, build(convo(2), effort = "low"), GEN)
        assertFalse(f.chained, "reasoning.effort is pinned per response; a change must not ride a chain")
    }

    /** BAIL: the prefix was rewritten (compaction, cache-key drift, an amended body). */
    @Test
    fun `a rewritten prefix full-sends`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(2)), "resp_1", GEN)
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
        s.completed(KEY, r, "resp_1", GEN)
        assertFalse(s.frameFor(KEY, r, GEN).chained)
    }

    /** BAIL: an unmodelled item shape anywhere in the suffix. Wrong-drop and wrong-send are both
     *  silent corruption; a full send is merely the status quo. */
    @Test
    fun `an unknown item type in the suffix full-sends`() {
        val s = ResponsesWsSession()
        val base = build(convo(1))
        s.completed(KEY, base, "resp_1", GEN)
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

    /** State is committed only on a clean terminal; a tear/cancel must not leave a chain behind. */
    @Test
    fun `a cleared conversation full-sends, and a null response id never commits`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN)
        s.cleared(KEY)
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN).chained)

        s.completed(KEY, build(convo(1)), null, GEN)
        assertFalse(s.frameFor(KEY, build(convo(2)), GEN).chained, "a terminal without an id is not chainable")
    }

    /** Chaining is per-conversation: one conversation's state never serves another. */
    @Test
    fun `chains are scoped per conversation key`() {
        val s = ResponsesWsSession()
        s.completed(KEY, build(convo(1)), "resp_1", GEN)
        assertFalse(s.frameFor("other-conv", build(convo(2)), GEN).chained)
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
