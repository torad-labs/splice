// The reasoning-continuation fold buffer: reasoning frames pass through LIVE; tentative final
// output (message text / tool calls) buffers until flush (with real block indices allocated THEN,
// after the live reasoning, so the wire stays monotonic) or is dropped on discard.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.gateway.wire.BufferingWireSink
import splice.spi.WireSink

private class RecordingSink : WireSink {
    val calls = mutableListOf<String>()
    private var next = 0

    override suspend fun openText(): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openText#${it.value}") }

    override suspend fun openThinking(): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openThinking#${it.value}") }

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openTool#${it.value}($id,$name)") }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        calls.add("text#${index.value}:$text")
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        calls.add("think#${index.value}:$thinking")
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        calls.add("json#${index.value}:$partialJson")
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        calls.add("close#${index.value}")
    }

    override suspend fun closeAll() {
        calls.add("closeAll")
    }

    override suspend fun addTextBlock(text: String) {
        calls.add("addText:$text")
    }

    override suspend fun addRedactedThinking(data: String) {
        calls.add("redacted:$data")
    }
}

class BufferingWireSinkTest {

    @Test
    fun `reasoning passes through live, final output buffers until flush with indices after reasoning`() = runTest {
        val real = RecordingSink()
        val buf = BufferingWireSink(real)

        val think = buf.openThinking()
        buf.thinkingDelta(think, "reasoning")
        buf.closeBlock(think)

        val text = buf.openText()
        buf.textDelta(text, "answer")
        buf.closeBlock(text)

        // before flush: ONLY the live reasoning reached the real sink; the text block is buffered
        assertEquals(listOf("openThinking#0", "think#0:reasoning", "close#0"), real.calls)

        buf.flush()
        // after flush: the text block replays with a REAL index (1) allocated AFTER the reasoning
        assertEquals(
            listOf(
                "openThinking#0",
                "think#0:reasoning",
                "close#0",
                "openText#1",
                "text#1:answer",
                "close#1",
            ),
            real.calls,
        )
    }

    @Test
    fun `discard drops the tentative output - reasoning already live, nothing else reaches the wire`() = runTest {
        val real = RecordingSink()
        val buf = BufferingWireSink(real)

        val think = buf.openThinking()
        buf.thinkingDelta(think, "reasoning")
        buf.closeBlock(think)

        val text = buf.openText()
        buf.textDelta(text, "tentative")
        buf.closeBlock(text)

        buf.discard()
        assertEquals(listOf("openThinking#0", "think#0:reasoning", "close#0"), real.calls)
    }

    @Test
    fun `tool calls buffer and replay in order on flush`() = runTest {
        val real = RecordingSink()
        val buf = BufferingWireSink(real)
        val tool = buf.openTool("t1", "run")
        buf.inputJsonDelta(tool, """{"a":1}""")
        buf.closeBlock(tool)
        assertTrue(real.calls.isEmpty(), "tool frames must not reach the wire before flush")

        buf.flush()
        assertEquals(listOf("openTool#0(t1,run)", """json#0:{"a":1}""", "close#0"), real.calls)
    }

    @Test
    fun `closeAll closes live reasoning immediately and defers buffered blocks to flush`() = runTest {
        val real = RecordingSink()
        val buf = BufferingWireSink(real)
        val think = buf.openThinking() // live, real index 0
        val text = buf.openText() // buffered placeholder
        buf.textDelta(text, "x") // buffered

        buf.closeAll()
        // live reasoning block closed now (via real.closeAll); buffered text NOT yet on the wire
        assertEquals(listOf("openThinking#0", "closeAll"), real.calls)

        buf.flush()
        // buffered text opens with a real index and is closed (closeAll deferred its close)
        assertEquals(
            listOf("openThinking#0", "closeAll", "openText#1", "text#1:x", "close#1"),
            real.calls,
        )
    }

    @Test
    fun `redacted thinking replay passes through live`() = runTest {
        val real = RecordingSink()
        val buf = BufferingWireSink(real)
        buf.addRedactedThinking("blob")
        assertEquals(listOf("redacted:blob"), real.calls)
    }
}
