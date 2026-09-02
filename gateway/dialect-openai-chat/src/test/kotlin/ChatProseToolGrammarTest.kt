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

    // codex-splice, DR-153 review: the arms above could not tell the real fix from a ROUTER-ONLY
    // one — closing prose in ChatEventRouter just before applyToolCall instead of in
    // openPendingTool. That mutant passed both deferred-path arms, because their fixtures emit
    // nothing between the slot's reservation and its actual open, so the premature close was
    // indistinguishable from the right one. Late prose in that window separates them: the correct
    // code keeps the prose block live and closes it when the tool is really born, while the mutant
    // closes an empty gap, lets the prose REOPEN a text block, and then opens the tool over it.
    @Test
    fun `prose between a nameless slot and its flush is closed at the real open - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"arguments":"{\"a\":1}"}}]}}]}""",
            """{"choices":[{"delta":{"content":"late prose"}}]}""",
            toolStop,
        )
        assertEquals(
            listOf("openText", "text:late prose", "close#0", "openTool:tool", "json:{\"a\":1}", "closeAll"),
            sink.calls,
            "the prose block must close as the deferred tool opens, not when its slot was reserved",
        )
    }

    // The fourth path to openPendingTool: a slot reserved by deltas that never carried a name, and
    // adopted by the FINAL message's echo. It reaches the open through applyFinalMessage rather
    // than applyDelta, so a close placed in the delta router never runs for it at all.
    @Test
    fun `prose between a nameless slot and its final echo is closed at the real open - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"arguments":"{\"a\":1}"}}]}}]}""",
            """{"choices":[{"delta":{"content":"late prose"}}]}""",
            """{"choices":[{"message":{"tool_calls":[{"id":"t1","function":{"name":"Read"}}]},""" +
                """"finish_reason":"tool_calls"}]}""",
        )
        assertEquals(
            listOf("openText", "text:late prose", "close#0", "openTool:Read", "json:{\"a\":1}", "closeAll"),
            sink.calls,
            "the final-echo open must close the prose block it opens over",
        )
    }

    // The OTHER half of the late-prose drop, and it was entirely unpinned: DR-153 guards
    // applyDeltaProse with hasToolUse, and foldFinalProse identically — but only the delta guard
    // had an arm, so making the final one unconditional passed the whole chat suite. A vendor that
    // repeats the turn's prose on the final message would then open a text block over the live
    // tool, which is the exact grammar violation the delta guard exists to prevent.
    @Test
    fun `final-message prose after a live tool is dropped, not interleaved - DR-153`() = runTest {
        val sink = Rec()
        drive(
            sink,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":""" +
                """{"name":"Read","arguments":"{\"a\":1}"}}]}}]}""",
            """{"choices":[{"message":{"content":"final prose"},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals(
            listOf("openTool:Read", "json:{\"a\":1}", "closeAll"),
            sink.calls,
            "no text block may open over a live tool on the final-message path either",
        )
        assertTrue(sink.calls.none { it.contains("final prose") }, "the dropped prose must not reach the wire")
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
