// NEW: V4-446 — a code-mode round chains on the WebSocket the way a function-call round does. Since
// V4-388 every GPT tool call is `exec`, a custom tool, and the delta classifier knew only function
// calls, so 199 of 199 traced code-mode continuations bailed to a full send of the whole
// conversation (parity audit 2026-09-29: 103 of 6,877 WebSocket rounds chained). The item shapes
// below are the live claudex wire's (trace of 2026-09-29), not the builder's: code mode's history is
// written by the codex provider, so the dialect's builder never emits them.
package splice.dialect.responses.v4446

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
import splice.dialect.responses.request.responsesRequestJson
import splice.dialect.responses.websocket.ResponsesWsSession
import splice.dialect.responses.websocket.WsFrame

private const val KEY = "conv-1"
private const val GEN = 3L

private fun obj(vararg pairs: Pair<String, String>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

private val USER = obj("role" to "user", "content" to "start")
private fun reasoning(n: Int) = obj("type" to "reasoning", "id" to "rs_$n", "encrypted_content" to "enc$n")
private fun exec(n: Int) = obj(
    "id" to "ctc_$n",
    "type" to "custom_tool_call",
    "status" to "completed",
    "call_id" to "call_$n",
    "input" to "await tools.Read({file_path: '/f$n'})",
    "name" to "exec",
)
private fun execOut(n: Int) = obj(
    "type" to "custom_tool_call_output",
    "call_id" to "call_$n",
    "output" to "Script completed\nOutput:\nf$n",
)
private fun developer(text: String) = obj("role" to "developer", "content" to text)

private fun request(items: List<JsonObject>): JsonObject = buildJsonObject {
    put("model", JsonPrimitive("gpt-6.1-sol"))
    put("input", JsonArray(items))
    put("parallel_tool_calls", JsonPrimitive(false))
}

private fun WsFrame.items(): List<JsonObject> =
    (responsesRequestJson.parseToJsonElement(json) as JsonObject)["input"]!!.jsonArray.map { it.jsonObject }

private fun WsFrame.previous(): String? =
    (responsesRequestJson.parseToJsonElement(json) as JsonObject)["previous_response_id"]?.jsonPrimitive?.content

private fun typesOf(items: List<JsonObject>): List<String> =
    items.map { it["type"]?.jsonPrimitive?.content ?: "message:${it["role"]?.jsonPrimitive?.content}" }

class CodeModeChainingTest {

    /** The one round GPT makes all day since V4-388: the model's exec call comes back answered. The
     *  server produced the reasoning and the exec call, so only the script's output is new. */
    @Test
    fun `an exec round chains and sends only the script output`() {
        val s = ResponsesWsSession()
        val r1 = request(listOf(USER))
        s.completed(KEY, r1, "resp_1", GEN, s.epochOf(KEY), pendingCalls = setOf("call_1"))
        val r2 = request(listOf(USER, reasoning(1), exec(1), execOut(1)))

        val f = s.frameFor(KEY, r2, GEN)

        assertTrue(f.chained, "a code-mode continuation must chain")
        assertEquals("resp_1", f.previous())
        assertEquals(listOf("custom_tool_call_output"), typesOf(f.items()))
        assertEquals("call_1", f.items().single()["call_id"]?.jsonPrimitive?.content)
    }

    /** Claude Code's context notes arrive as developer messages beside the output (V4-390): new
     *  client input, so they ride the delta in order. */
    @Test
    fun `a developer note after the output rides the delta in order`() {
        val s = ResponsesWsSession()
        val r1 = request(listOf(USER, reasoning(1), exec(1), execOut(1)))
        s.completed(KEY, r1, "resp_2", GEN, s.epochOf(KEY), pendingCalls = setOf("call_2"))
        val earlier = r1["input"]!!.jsonArray.map { it.jsonObject }
        val r2 = request(earlier + listOf(reasoning(2), exec(2), execOut(2), developer("a peer wrote")))

        val f = s.frameFor(KEY, r2, GEN)

        assertTrue(f.chained)
        assertEquals(listOf("custom_tool_call_output", "message:developer"), typesOf(f.items()))
    }

    /** The server refuses a continuation that leaves its exec call unanswered (Claude Code's
     *  compaction fires between a call and its result): that still full-sends, and says why. */
    @Test
    fun `an exec call the round leaves unanswered still full-sends`() {
        val s = ResponsesWsSession()
        val r1 = request(listOf(USER))
        s.completed(KEY, r1, "resp_1", GEN, s.epochOf(KEY), pendingCalls = setOf("call_1"))
        val r2 = request(listOf(USER, developer("compact now")))

        val f = s.frameFor(KEY, r2, GEN)

        assertFalse(f.chained)
        assertEquals(2, f.items().size, "the full input")
        assertTrue(f.fullSendReason.orEmpty().contains("call_1"))
    }

    /** A rewritten prefix is a history the server never held: full send, whatever the item types. */
    @Test
    fun `a rewritten prefix with exec items still full-sends`() {
        val s = ResponsesWsSession()
        val r1 = request(listOf(USER, reasoning(1), exec(1), execOut(1)))
        s.completed(KEY, r1, "resp_1", GEN, s.epochOf(KEY), pendingCalls = setOf("call_2"))
        val rewritten = request(listOf(USER, reasoning(1), exec(1), execOut(9), reasoning(2), exec(2), execOut(2)))

        val f = s.frameFor(KEY, rewritten, GEN)

        assertFalse(f.chained)
        assertEquals(7, f.items().size)
    }
}
