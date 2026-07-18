// NEW: unit test the Anthropic-SSE -> WireSink passthrough machine — block re-indexing, the
// signature-synthesis-exactly-once contract, +cache_read usage normalization, stop_reason mapping,
// ignored-block swallowing, L3 truncation honesty, JsonNull safety. Mirrors ChatStreamTranslatorTest.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.dialect.passthrough.PassthroughStreamTranslator
import splice.dialect.passthrough.PassthroughTurnContext
import splice.spi.WireSink

private class Rec : WireSink {
    val calls = mutableListOf<String>()
    val toolOpens = mutableListOf<Pair<String, String>>()
    private var n = 0
    override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
    override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(n++).also {
        calls.add("openTool:$name")
        toolOpens.add(id to name)
    }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json:$partialJson") }
    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) { calls.add("sig:$signature") }
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun ctx() = PassthroughTurnContext({ false }, { null }, 180_000, 900_000)

private suspend fun drive(sink: Rec, vararg evs: JsonObject): TurnOutcome =
    PassthroughStreamTranslator(ctx()).driveTurn(evs.toList().asFlow(), sink)

// thinking (no upstream signature) -> text -> tool_use -> stop_reason tool_use -> stop.
private fun fullTurnEvents(): List<JsonObject> = listOf(
    ev("""{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":80}}}"""),
    ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
    ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"th1"}}"""),
    ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"th2"}}"""),
    ev("""{"type":"content_block_stop","index":0}"""),
    ev("""{"type":"content_block_start","index":1,"content_block":{"type":"text"}}"""),
    ev("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}"""),
    ev("""{"type":"content_block_stop","index":1}"""),
    ev(
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_x","name":"run"}}""",
    ),
    ev(
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
    ),
    ev("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"1}"}}"""),
    ev("""{"type":"content_block_stop","index":2}"""),
    ev("""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}"""),
    ev("""{"type":"message_stop"}"""),
)

class PassthroughStreamTranslatorTest {

    @Test
    fun `full turn re-indexes blocks, synthesizes one signature, and normalizes usage`() = runTest {
        val sink = Rec()
        val outcome = PassthroughStreamTranslator(ctx()).driveTurn(fullTurnEvents().asFlow(), sink)
        val s = outcome as TurnOutcome.Success
        assertEquals(
            listOf(
                "openThinking",
                "think:th1",
                "think:th2",
                "sig:splice-synth-v1",
                "close",
                "openText",
                "text:Hello",
                "close",
                "openTool:run",
                "json:{\"a\":",
                "json:1}",
                "close",
                "closeAll",
            ),
            sink.calls,
        )
        assertTrue(s.hasToolUse)
        assertEquals("toolu_x", sink.toolOpens.single().first) // tool id round-trips verbatim
        assertEquals(180, s.usage.inputTokens) // 100 + cache_read 80 (re-added for HeadServer)
        assertEquals(80, s.usage.cachedTokens)
        assertEquals(42, s.usage.outputTokens)
        assertEquals("th1th2", s.thinkingText)
        assertEquals("Hello", s.bodyText)
    }

    @Test
    fun `an upstream signature is forwarded and NOT doubled by synthesis at close`() = runTest {
        val sink = Rec()
        drive(
            sink,
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"t"}}"""),
            ev(
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"real-sig"}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertEquals(1, sink.calls.count { it.startsWith("sig:") })
        assertTrue(sink.calls.contains("sig:real-sig"))
        assertFalse(sink.calls.contains("sig:splice-synth-v1"))
        // order: signature forwarded before the block closes
        assertTrue(sink.calls.indexOf("sig:real-sig") < sink.calls.indexOf("close"))
    }

    @Test
    fun `error event maps to the matching ErrorType`() = runTest {
        val cases = mapOf(
            "overloaded_error" to ErrorType.OVERLOADED,
            "rate_limit_error" to ErrorType.RATE_LIMIT,
            "authentication_error" to ErrorType.AUTHENTICATION,
            "invalid_request_error" to ErrorType.INVALID_REQUEST,
            "teapot_error" to ErrorType.API_ERROR,
        )
        cases.forEach { (wire, expected) ->
            val outcome = drive(Rec(), ev("""{"type":"error","error":{"type":"$wire","message":"boom"}}"""))
            val f = outcome as TurnOutcome.Failure
            assertEquals(expected, f.type, "for $wire")
            assertTrue(f.message.contains("boom"))
        }
    }

    @Test
    fun `no message_stop is a retryable truncation failure`() = runTest {
        val outcome = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}"""),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.message.contains("truncated"))
    }

    @Test
    fun `stop_reason max_tokens marks the turn incomplete`() = runTest {
        val outcome = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cut"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":5}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val s = outcome as TurnOutcome.Success
        assertTrue(s.incomplete)
        assertFalse(s.hasToolUse)
    }

    @Test
    fun `ignored block types swallow their deltas and open nothing`() = runTest {
        val sink = Rec()
        val outcome = drive(
            sink,
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"s1"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("closeAll"), sink.calls) // nothing opened, nothing closed
    }

    @Test
    fun `explicit JSON nulls never leak into buffers`() = runTest {
        val sink = Rec()
        val outcome = drive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":null}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"real"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("real", s.bodyText)
        assertFalse(s.bodyText.contains("null"))
        assertEquals(10, s.usage.inputTokens)
    }
}
