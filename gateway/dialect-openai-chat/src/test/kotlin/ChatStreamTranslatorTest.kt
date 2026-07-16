// NEW: unit test the chat stream machine in isolation (asFlow -> RecordingSink) — reasoning +
// text + tool_calls + finish_reason mapping, truncated + failure paths.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatTurnContext
import splice.spi.WireSink

private class Rec : WireSink {
    val calls = mutableListOf<String>()
    private var n = 0
    override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
    override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(n++).also { calls.add("openTool:$name") }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json:$partialJson") }
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun ctx() = ChatTurnContext({ false }, { null }, 180_000, 900_000)

class ChatStreamTranslatorTest {

    @Test
    fun `reasoning, text, finish stop`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"reasoning_content":"why"}}]}"""),
                ev("""{"choices":[{"delta":{"content":"Hi "}}]}"""),
                ev("""{"choices":[{"delta":{"content":"there"}}]}"""),
                ev(
                    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("Hi there", s.bodyText)
        assertEquals("why", s.thinkingText)
        assertEquals(5, s.usage.inputTokens)
        assertTrue(sink.calls.contains("openThinking"))
        assertTrue(sink.calls.contains("text:Hi "))
    }

    @Test
    fun `tool_calls stream by index and map to tool_use`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"run","arguments":"{\"a\":"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("openTool:run", "json:{\"a\":", "json:1}", "closeAll"), sink.calls)
    }

    @Test
    fun `truncated without finish is overloaded`() = runTest {
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(ev("""{"choices":[{"delta":{"content":"partial"}}]}""")).asFlow(),
            Rec(),
        )
        assertTrue(outcome is TurnOutcome.Failure)
    }

    @Test
    fun `error frame is a failure`() = runTest {
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(ev("""{"error":{"message":"model overloaded"}}""")).asFlow(),
            Rec(),
        )
        val f = outcome as TurnOutcome.Failure
        assertTrue(f.message.contains("model overloaded"))
    }
}
