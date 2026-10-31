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
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.RefusedBy
import splice.core.failure.Tear
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.head.testVoice
import splice.upstream.transport.BufferCapacity

/** Failure-handling slice 4: a buffered answer that ended in the R family answers 529 overloaded_error, the one
 *  status the client reaction record shows 2.1.310 sends a non-stream request again on (http-529-non-stream,
 *  final-design.md section 11). Before it, an api_error read 502, which no row of the record covers. */
private const val RESENT_STATUS = 529

class CollectingTerminalTest {

    private fun terminal(usagePayload: (Usage?) -> JsonObject = { _ -> buildJsonObject { } }) =
        CollectingTerminal(model = "claude-kimi--k3", usagePayload = usagePayload, voice = testVoice)

    /** The words of [t]'s error body. */
    private fun wordsOf(t: CollectingTerminal): String? =
        t.responseBody()["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

    /** The line a buffered answer ends with for [cause]: told before the client has read anything ([collected] is
     *  whether the provider's answer had begun when the collect ended). */
    private fun line(cause: FailureCause, collected: Boolean = false) =
        testVoice.ending(cause, Position(Moment.NON_STREAM, streamBegan = collected))

    // HEAD-004/REG-001: a blank tool name has no safe stand-in (unlike a blank id, which is
    // synthesized) — the block is dropped, but stop_reason=tool_use still claims a tool call
    // happened. Shipping that as a clean 200 with an empty content array is protocol-invalid and
    // silently discards the tool call; the turn must fail honestly instead.
    @Test
    fun `a permanent failure returns an error status and body without assistant content`() = runTest {
        val t = terminal()
        val cause = FailureCause.Provider.Refused(RefusedBy.FILTER)
        t.emitError(cause)
        assertEquals(400, t.httpStatus())
        val body = t.responseBody()
        assertEquals("error", body["type"]?.jsonPrimitive?.content)
        assertEquals("invalid_request_error", body.getValue("error").jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(line(cause).text, wordsOf(t))
        assertEquals(line(cause), t.lineWritten)
        assertTrue("content" !in body)
        assertTrue("stop_reason" !in body)
    }

    @Test
    fun `a blank-name tool_use fails the turn honestly instead of a clean 200`() = runTest {
        val t = terminal()
        t.openTool(id = "toolu_1", name = "")
        t.emitTerminal(hasToolUse = true, incomplete = false, usage = Usage())
        assertEquals(RESENT_STATUS, t.httpStatus())
        val body = t.responseBody()
        assertEquals("error", body["type"]?.jsonPrimitive?.content)
        assertEquals(
            ErrorType.OVERLOADED.wireName,
            body["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
        // The provider's tool call tore: the body says the torn-tool line, as the trace does.
        val torn = line(FailureCause.Provider.ToolTear(runaway = false), collected = true)
        assertEquals(torn.text, wordsOf(t))
        assertEquals(torn, t.lineWritten)
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
        assertEquals(RESENT_STATUS, t.httpStatus())
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

        assertEquals(RESENT_STATUS, t.httpStatus())
        assertEquals(line(FailureCause.Provider.ToolTear(runaway = true), collected = true).text, wordsOf(t))
    }

    /** Failure-handling slice 2: every error body this terminal builds says its voice's line, the fallback for a
     *  drive that ended in neither verb included, and a clean answer writes no line. */
    @Test
    fun `a drive that ended in neither verb answers with the line for an escaped failure`() = runTest {
        val t = terminal()
        t.abandon()
        assertEquals(RESENT_STATUS, t.httpStatus())
        assertEquals(line(FailureCause.Splice.Internal(escaped = true)).text, wordsOf(t))
        assertEquals(null, t.lineWritten, "no ending wrote this line")
    }

    @Test
    fun `a clean answer and an error after it write no line`() = runTest {
        val t = terminal()
        t.addTextBlock("done")
        t.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())
        t.emitError(FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = false))
        assertEquals(200, t.httpStatus())
        assertEquals(null, t.lineWritten)
        assertEquals(line(FailureCause.Splice.Internal()), t.lineFor(FailureCause.Splice.Internal()))
    }

    // A buffered answer has no committed 200 and no in-band event: the HTTP status IS the information. Slice 4
    // chooses it by family rather than by the cause's own type, from what the client reaction record shows 2.1.310
    // does with each status (final-design.md section 11): a limit reaching the client keeps the 429 it sends again,
    // and a failure whose own type was api_error now answers 529 if the provider's and 400 if the request's. 502,
    // which the record does not cover, is gone.
    @Test
    fun `a buffered rate limit keeps its real 429`() = runTest {
        val t = terminal()
        val cause = FailureCause.Provider.Limited(Lift.Unstated, window = null)
        t.emitError(cause)
        assertEquals(429, t.httpStatus())
        assertEquals(line(cause).text, wordsOf(t))
        assertEquals(
            ErrorType.RATE_LIMIT.wireName,
            t.responseBody()["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }

    /** Failure-handling slice 4, replacing `a buffered api error keeps its real 502, permanent or not`: whose error
     *  it is decides the answer, since the request's own verdict earns itself again however many times it is sent. */
    @Test
    fun `a buffered failure answers by whose error it is`() = runTest {
        val provider = terminal()
        val transient = FailureCause.Provider.Unrecognized(clientError = false)
        provider.emitError(transient)
        assertEquals(RESENT_STATUS, provider.httpStatus())
        assertEquals(
            ErrorType.OVERLOADED.wireName,
            provider.responseBody()["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertEquals(line(transient).text, wordsOf(provider))

        val request = terminal()
        request.emitError(FailureCause.Provider.Unrecognized(clientError = true))
        assertEquals(400, request.httpStatus())
        assertEquals(
            ErrorType.INVALID_REQUEST.wireName,
            request.responseBody()["error"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }
}
