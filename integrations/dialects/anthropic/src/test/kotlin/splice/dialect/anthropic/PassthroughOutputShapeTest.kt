package splice.dialect.anthropic

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.upstream.sse.WireSink

/** Counts wire activity without retaining any payload. */
private class ShapeSink : WireSink {
    var opens = 0
    var closes = 0
    var closeAlls = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(opens++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(opens++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(opens++)
    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = WireBlockIndex(opens++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) { closes++ }
    override suspend fun closeAll() { closeAlls++ }
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun frame(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
private fun start(index: Int, kind: String): JsonObject = buildJsonObject {
    put("type", "content_block_start")
    put("index", index)
    put("content_block", buildJsonObject { put("type", kind) })
}
private fun stop(index: Int): JsonObject = buildJsonObject {
    put("type", "content_block_stop")
    put("index", index)
}
private val END = listOf(
    frame("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""),
    frame("""{"type":"message_stop"}"""),
)
private const val ZERO_INPUT = "input_tokens=0 cache_read_input_tokens=0 cache_creation_input_tokens=0 output_tokens=2"

class PassthroughOutputShapeTest {
    private suspend fun drive(
        events: List<JsonObject>,
        sink: ShapeSink = ShapeSink(),
        quirks: PassthroughQuirks = PassthroughQuirks("synthetic"),
    ): TurnOutcome.Success = PassthroughStreamTranslator(
        PassthroughTurnContext({ false }, { null }, 180_000, 900_000),
        quirks,
    ).driveTurn(events.asFlow(), sink) as TurnOutcome.Success

    @Test
    fun `an empty end_turn carries structural evidence without accepting the empty message`() = runTest {
        val sink = ShapeSink()
        val success = drive(
            listOf(frame("""{"type":"message_start","message":{"usage":{"input_tokens":17}}}""")) + END,
            sink,
        )
        val shape = "opened=[] closed=[] stop_reason=end_turn " +
            "input_tokens=17 cache_read_input_tokens=0 cache_creation_input_tokens=0 output_tokens=2"
        assertAll(
            { assertEquals(shape, success.outputShape) },
            { assertFalse(success.messageClosed, "closure must not silently change empty-turn acceptance") },
            { assertFalse(success.emittedText) },
            { assertFalse(success.emittedThinking) },
            { assertEquals(0, sink.opens) },
            { assertEquals(1, sink.closeAlls) },
        )
    }

    @Test
    fun `every known backend kind records its own start and stop`() = runTest {
        val kinds = listOf(
            "text",
            "thinking",
            "redacted_thinking",
            "tool_use",
            "server_tool_use",
            "web_search_tool_result",
        )
        val events = kinds.flatMapIndexed { index, kind -> listOf(start(index, kind), stop(index)) }
        val shape = "text:1,thinking:1,redacted_thinking:1,tool_use:1,server_tool_use:1,web_search_tool_result:1"
        assertEquals(
            "opened=[$shape] closed=[$shape] stop_reason=end_turn $ZERO_INPUT",
            drive(events + END).outputShape,
        )
    }

    @Test
    fun `local retirement and closeAll do not invent backend stops`() = runTest {
        val sink = ShapeSink()
        val success = drive(listOf(start(0, "text"), start(0, "thinking")) + END, sink)
        assertEquals(2, sink.opens)
        assertEquals(1, sink.closes, "the replaced block was retired locally")
        assertEquals(1, sink.closeAlls)
        assertEquals(
            "opened=[text:1,thinking:1] closed=[] stop_reason=end_turn $ZERO_INPUT",
            success.outputShape,
        )
    }

    @Test
    fun `reused indices count each real lifecycle without counting stray stops`() = runTest {
        val events = listOf(
            stop(99),
            start(3, "thinking"),
            stop(3),
            stop(3),
            start(3, "text"),
            stop(3),
        )
        assertEquals(
            "opened=[thinking:1,text:1] closed=[thinking:1,text:1] stop_reason=end_turn $ZERO_INPUT",
            drive(events + END).outputShape,
        )
    }

    @Test
    fun `backend blocks swallowed by a profile remain visible in the shape`() = runTest {
        val sink = ShapeSink()
        val success = drive(
            listOf(start(0, "server_tool_use"), stop(0), start(1, "web_search_tool_result"), stop(1)) + END,
            sink,
            KimiProfileFixture().kimi("synthetic"),
        )
        assertEquals(0, sink.opens, "the profile must actually swallow these blocks")
        assertEquals(
            "opened=[server_tool_use:1,web_search_tool_result:1] " +
                "closed=[server_tool_use:1,web_search_tool_result:1] stop_reason=end_turn $ZERO_INPUT",
            success.outputShape,
        )
    }

    @Test
    fun `shape usage stays disjoint and a usage-only delta preserves the stop reason`() = runTest {
        val events = listOf(
            frame(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":20,""" +
                    """"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}}""",
            ),
            frame("""{"type":"message_delta","delta":{"stop_reason":"stop_sequence"},"usage":{"output_tokens":1}}"""),
            frame(
                """{"type":"message_delta","usage":{"output_tokens":2,"cache_creation_input_tokens":12,""" +
                    """"cache_creation":{"ephemeral_5m_input_tokens":99}}}""",
            ),
            frame("""{"type":"message_stop"}"""),
        )
        val success = drive(events)
        assertEquals(42, success.usage.inputTokens)
        assertEquals(20, success.usage.cachedTokens)
        assertEquals(12, success.usage.cacheWriteTokens)
        assertEquals(
            "opened=[] closed=[] stop_reason=stop_sequence " +
                "input_tokens=10 cache_read_input_tokens=20 cache_creation_input_tokens=12 output_tokens=2",
            success.outputShape,
        )
    }

    @Test
    fun `unknown discriminators and content never enter structural evidence`() = runTest {
        val privateValue = "synthetic-private-shape\nnot-a-log-line"
        val events = listOf(
            start(0, privateValue),
            stop(0),
            buildJsonObject {
                put("type", "content_block_start")
                put("index", 1)
                put(
                    "content_block",
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", privateValue)
                        put("name", privateValue)
                        put("text", privateValue)
                        put("thinking", privateValue)
                        put("data", privateValue)
                    },
                )
            },
            stop(1),
            buildJsonObject {
                put("type", "message_delta")
                put("delta", buildJsonObject { put("stop_reason", privateValue) })
            },
            frame("""{"type":"message_stop"}"""),
        )
        val shape = drive(events).outputShape
        assertEquals(
            "opened=[other:1,tool_use:1] closed=[other:1,tool_use:1] stop_reason=other " +
                "input_tokens=0 cache_read_input_tokens=0 cache_creation_input_tokens=0 output_tokens=0",
            shape,
        )
        assertFalse(shape.contains(privateValue))
        assertFalse(shape.contains('\n'))
    }

    @Test
    fun `missing indices and null stop reasons do not invent block lifecycles`() = runTest {
        val events = listOf(
            frame("""{"type":"content_block_start","content_block":{"type":"thinking"}}"""),
            frame("""{"type":"content_block_stop"}"""),
            frame("""{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":2}}"""),
            frame("""{"type":"message_stop"}"""),
        )
        val success = drive(events)
        assertTrue(success.outputShape.startsWith("opened=[] closed=[] stop_reason=none "))
        assertEquals("opened=[] closed=[] stop_reason=none $ZERO_INPUT", success.outputShape)
    }
}
