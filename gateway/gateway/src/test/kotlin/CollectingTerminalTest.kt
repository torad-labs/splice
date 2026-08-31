// NEW: unit test CollectingTerminal (the stream:false TurnTerminal). Review findings HEAD-003 /
// HEAD-004 / REG-001: a tool_use whose accumulated input never parsed as JSON, or whose upstream
// name was blank, must never reach the client as a clean 200 — a stop_reason=tool_use with a
// silently emptied/dropped tool call is a wrong action taken on the user's machine (L3 honesty).
// Mirrors SseEmitterTest's construction idiom for the streaming sink.
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
import splice.gateway.wire.CollectingTerminal
import splice.spi.BufferCapacity

private const val ERROR_STATUS = 502

class CollectingTerminalTest {

    private fun terminal(usagePayload: (Usage?) -> JsonObject = { _ -> buildJsonObject { } }) =
        CollectingTerminal(model = "claude-kimi--k3", usagePayload = usagePayload)

    // HEAD-004/REG-001: a blank tool name has no safe stand-in (unlike a blank id, which is
    // synthesized) — the block is dropped, but stop_reason=tool_use still claims a tool call
    // happened. Shipping that as a clean 200 with an empty content array is protocol-invalid and
    // silently discards the tool call; the turn must fail honestly instead.
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
}
