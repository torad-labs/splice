// NEW: DR-153 — the TOOL half of DR-143's one-block grammar defect. DR-143 stopped text and
// thinking overlapping EACH OTHER; a tool_use block opening over live prose was the same Anthropic
// violation and was left standing, on all three paths that reach openPendingTool. These arms assert
// the EXACT call sequence, because membership cannot distinguish a compliant stream from an
// overlapping one — both contain openText and openTool.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.chat.ChatStreamTranslator

class ChatProseToolGrammarTest {

    private suspend fun drive(sink: Rec, vararg evs: String) =
        ChatStreamTranslator(ctx()).driveTurn(evs.map { ev(it) }.asFlow(), sink)

    private val toolStop = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""

    @Test
    fun `a text block is closed before a tool block opens - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"content":"Let me look"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"Read","arguments":"{}"}}]}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openText", "text:Let me look", "close#0", "openTool:Read", "json:{}", "closeAll"),
            sink.calls,
        )
    }

    @Test
    fun `a thinking block is closed before a tool block opens - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"reasoning_content":"weighing"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"Read","arguments":"{}"}}]}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openThinking", "think:weighing", "close#0", "openTool:Read", "json:{}", "closeAll"),
            sink.calls,
        )
    }

    // Prose still runs BEFORE tool_calls within one frame, so a frame carrying both emits the prose
    // and THEN closes it as the tool opens. The router's statement order is unchanged by DR-153.
    @Test
    fun `prose and the first tool in one frame stay in order - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"content":"ok","tool_calls":[{"index":0,"id":"t1",""" +
                """"function":{"name":"Read","arguments":"{}"}}]}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openText", "text:ok", "close#0", "openTool:Read", "json:{}", "closeAll"),
            sink.calls,
        )
    }

    // The DEFERRED path: a call whose name never arrives is buffered and only opened by the
    // finish_reason flush. A close in the delta path alone would leave this one overlapping.
    @Test
    fun `the finish-reason flush closes prose before opening the deferred tool - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"content":"thinking about it"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"arguments":"{\"a\":1}"}}]}}]}""",
            toolStop,
        )
        val expected = listOf(
            "openText",
            "text:thinking about it",
            "close#0",
            "openTool:tool",
            "json:{\"a\":1}",
            "closeAll",
        )
        assertEquals(expected, sink.calls)
    }

    // Late prose, after a tool block is already live. OpenAI chat has no per-tool stop event, so
    // opening a prose block would mean closing the tool first — and a later argument delta would
    // then land on a closed block and vanish. The prose is dropped; every argument byte survives.
    @Test
    fun `prose arriving after a tool block is dropped, not interleaved - DR-153`() = runTest {
        val sink = Rec()
        val outcome = drive(
            sink,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"Read","arguments":"{"}}]}}]}""",
            """{"choices":[{"delta":{"content":"late prose"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openTool:Read", "json:{", "json:}", "closeAll"),
            sink.calls,
            "no text block may open over a live tool, and the tool's later args must still land",
        )
        assertTrue(sink.calls.none { it.contains("late prose") }, "the dropped prose must not reach the wire")
        assertTrue(outcome is splice.core.turn.TurnOutcome.Success)
    }

    // Sibling tools are deliberately NOT closed when the next one opens. OpenAI interleaves
    // arguments across parallel calls, so closing tool 0 to open tool 1 would silently drop tool 0's
    // remaining args — a worse failure than the overlap. Scoped out of DR-153 on purpose.
    @Test
    fun `parallel sibling tools are not closed by each other - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"Read","arguments":"{"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"t2","function":{"name":"Grep","arguments":"{"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"}"}}]}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openTool:Read", "json:{", "openTool:Grep", "json:{", "json:}", "json:}", "closeAll"),
            sink.calls,
            "interleaved parallel args must both land; no close between sibling tools",
        )
    }
}
