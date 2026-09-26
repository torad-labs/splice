// NEW: unit test CollectingTerminal (the stream:false TurnTerminal). Review findings HEAD-003 /
// HEAD-004 / REG-001: a tool_use whose accumulated input never parsed as JSON, or whose upstream
// name was blank, must never reach the client as a clean 200 — a stop_reason=tool_use with a
// silently emptied/dropped tool call is a wrong action taken on the user's machine (L3 honesty).
// Mirrors SseEmitterTest's construction idiom for the streaming sink.
package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.upstream.transport.BufferCapacity

private const val ERROR_STATUS = 502
private const val OK_STATUS = 200

class CollectingTerminalTest {

    private fun terminal(usagePayload: (Usage?) -> JsonObject = { _ -> buildJsonObject { } }) =
        CollectingTerminal(model = "claude-kimi--k3", usagePayload = usagePayload)

    // HEAD-004/REG-001: a blank tool name has no safe stand-in (unlike a blank id, which is
    // synthesized) — the block is dropped, but stop_reason=tool_use still claims a tool call
    // happened. Shipping that as a clean 200 with an empty content array is protocol-invalid and
    // silently discards the tool call; the turn must fail honestly instead.
    @Test
    fun `explained ending is a clean 200 message carrying the explanation as text`() = runTest {
        val t = terminal()
        t.emitExplained("\u26A0 splice: explained", Usage())
        assertEquals(OK_STATUS, t.httpStatus())
        val body = t.responseBody()
        assertEquals("message", body["type"]?.jsonPrimitive?.content)
        assertEquals("end_turn", body["stop_reason"]?.jsonPrimitive?.content)
        val block = body.getValue("content").jsonArray.single().jsonObject
        assertEquals("text", block["type"]?.jsonPrimitive?.content)
        assertEquals("\u26A0 splice: explained", block["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a blank-name tool_use fails the turn honestly instead of a clean 200`() = runTest {
        val t = terminal()
        t.openTool(id = "toolu_1", name = "")
        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage())
        assertEquals(ERROR_STATUS, t.httpStatus())
        val body = t.responseBody()
        assertEquals("error", body["type"]?.jsonPrimitive?.content)
        assertEquals(
            ErrorType.API_ERROR.wireName,
            body["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertTrue(
            body["error"]!!.jsonObject["message"]?.jsonPrimitive?.content.orEmpty().contains("tool_use"),
        )
    }

    // RG2-001: the malformed-tool-use error envelope used to drop the turn's usage entirely — the
    // client was billed for a turn whose wire response then lost the token accounting.
    @Test
    fun `the malformed tool_use error envelope still carries the turn's usage`() = runTest {
        val t = terminal(usagePayload = { u -> buildJsonObject { put("input_tokens", u?.inputTokens ?: -1) } })
        t.openTool(id = "toolu_1", name = "")
        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage(inputTokens = 42))
        val body = t.responseBody()
        assertEquals(42, body["usage"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
    }

    // A whitespace-only name is blank too (String.isBlank), same honest-failure path.
    @Test
    fun `a whitespace-only tool_use name also fails the turn honestly`() = runTest {
        val t = terminal()
        t.openTool(id = "toolu_1", name = "   ")
        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage())
        assertEquals(ERROR_STATUS, t.httpStatus())
        assertEquals("error", t.responseBody()["type"]?.jsonPrimitive?.content)
    }

    // Sanity/contrast: a properly named tool_use is unaffected and still emits a clean terminal.
    @Test
    fun `a named tool_use still emits a clean terminal message`() = runTest {
        val t = terminal()
        val idx = t.openTool(id = "toolu_1", name = "run")
        t.inputJsonDelta(idx, """{"a":1}""")
        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage())
        assertEquals(200, t.httpStatus())
        val body = t.responseBody()
        assertEquals("message", body["type"]?.jsonPrimitive?.content)
        val block = body["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_use", block["type"]?.jsonPrimitive?.content)
        assertEquals("run", block["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `aggregate tool input across non-stream blocks is capacity bounded`() = runTest {
        val t = terminal()
        val args = """{"payload":"${"x".repeat(1_000_000)}"}"""
        repeat(BufferCapacity.MAX_BUFFERED_CHARS / args.length + 2) { index ->
            val block = t.openTool(id = "toolu_$index", name = "run")
            t.inputJsonDelta(block, args)
        }

        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage())

        assertEquals(ERROR_STATUS, t.httpStatus())
        val message = t.responseBody()["error"]!!.jsonObject["message"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(message.contains("exceeded max buffered size"), message)
    }

    // V4-81: THE COLLECT PATH IS OUT OF THE PRE-CONTENT WIRE-TYPE RULE BY CONSTRUCTION, and these
    // two pins are what say so out loud. The rule exists for the committed-200 STREAMING path, where
    // the only lever left after the status is committed is the event type inside the body. On
    // `stream:false` there is no committed 200 and no in-band event: the HTTP status IS the
    // information, and relabelling it would be a lie about a condition that did not happen — a
    // genuine 429 shipped as a 529 with rate-limit headers attached, or a buffered api_error
    // claiming an overload. The rule was relocated to SseEmitter.emitError (V4-81) precisely so
    // that this terminal cannot inherit it: the two are separate implementations of TurnTerminal,
    // so there is no shared code path to forget about.
    @Test
    fun `a buffered rate limit keeps its real 429 instead of the streaming relabel - V4-81`() = runTest {
        val t = terminal()
        t.emitError(ErrorType.RATE_LIMIT, "upstream: quota exhausted")
        assertEquals(429, t.httpStatus())
        assertEquals(
            ErrorType.RATE_LIMIT.wireName,
            t.responseBody()["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a buffered api error keeps its real 502, permanent or not - V4-81`() = runTest {
        val remappable = terminal()
        remappable.emitError(ErrorType.API_ERROR, "upstream: broke")
        assertEquals(ERROR_STATUS, remappable.httpStatus())

        // The parameter the streaming terminal reads is DELIBERATELY INERT here: the collect path
        // has no retryable event to relabel, so permanence changes nothing about its status.
        val permanent = terminal()
        permanent.emitError(ErrorType.API_ERROR, "upstream: model refused", permanent = true)
        assertEquals(ERROR_STATUS, permanent.httpStatus())
        assertEquals(
            ErrorType.API_ERROR.wireName,
            permanent.responseBody()["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }
}
