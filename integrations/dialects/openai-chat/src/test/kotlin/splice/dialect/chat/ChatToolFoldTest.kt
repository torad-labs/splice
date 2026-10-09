// The final-message tool-fold cases, split out of ChatStreamTranslatorTest (which hit the detekt
// LargeClass ceiling): a tool whose name and args arrive only on the consolidated final message, a
// nameless final-only call, the prose block closing before a tool block opens, args the stream cut short completed
// from the final copy, and a call streamed without an id matched to its echo.
package splice.dialect.chat

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
import splice.upstream.sse.WireSink

private class FoldRec : WireSink {
    val calls = mutableListOf<String>()
    val toolOpens = mutableListOf<Pair<String, String>>() // (id, name) — inspect ids without disturbing `calls`
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

    // DR-153: was a bare "close". An index-blind record cannot tell WHICH block was closed, so an
    // assertion that a tool open closed the PROSE block could not be written at all — the same
    // fake-green shape DR-143 fixed in Rec.
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close#${index.value}") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun foldEv(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun foldCtx() = ChatTurnContext({ false }, { null }, 180_000, 900_000)

class ChatToolFoldTest {

    // DR-153: the FOURTH entry, and the one a delta-path-only close still misses. Here the final
    // message does NOT mint a new call — it adopts a PENDING slot reserved by an earlier nameless
    // delta, so ChatFinalToolFold calls openPendingTool DIRECTLY rather than routing back through
    // applyToolCall. Found while attributing a mutant: the close-in-the-delta-path-only mutant
    // reddened the flush arm but not the final-fold one, because that arm's call had no pending
    // slot. Without this arm the mutant's coverage claim would have been overstated.
    @Test
    fun `a final echo adopting a pending slot closes the prose block first`() = runTest {
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv("""{"choices":[{"delta":{"content":"answer first"}}]}"""),
                foldEv("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{}}]}}]}"""),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        val expected = listOf(
            "openText",
            "text:answer first",
            "close#0",
            "openTool:run",
            "json:{\"x\":1}",
            "closeAll",
        )
        assertEquals(expected, sink.calls)
    }

    // DR-153, the THIRD path into openPendingTool and the one FoldRec could not previously express:
    // a tool present only in the final consolidated message, opened while a streamed text block is
    // still live. Before the fix the tool_use opened over the open text block; a close in the delta
    // path alone would still miss this. FoldRec's close now carries its index, which is what makes
    // "the TEXT block (0) closed, and before the tool opened" writable at all.
    @Test
    fun `a final-only tool closes the streamed prose block before opening`() = runTest {
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv("""{"choices":[{"delta":{"content":"answer first"}}]}"""),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t9","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        val expected = listOf(
            "openText",
            "text:answer first",
            "close#0",
            "openTool:run",
            "json:{\"x\":1}",
            "closeAll",
        )
        assertEquals(expected, sink.calls)
    }

    @Test
    fun `final-message name and args both final-only open the tool with real input not empty`() = runTest {
        // A delta reserves the slot by id but streams neither function.name NOR arguments; both
        // arrive only in the trailing consolidated message. The pending slot must adopt the name
        // AND the final entry's arguments — opening with real input, never an empty {} (finding 3).
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{}}]}}]}"""),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("t1" to "run"), sink.toolOpens)
        assertEquals(1, sink.calls.count { it == "openTool:run" })
        // the tool opens with the final entry's real arguments, not an empty input block
        assertEquals(listOf("json:{\"x\":1}"), sink.calls.filter { it.startsWith("json:") })
    }

    @Test
    fun `a nameless final-only tool call alongside an echo is surfaced, not dropped`() = runTest {
        // Superset final message: t1 was streamed (echo SUPPRESSED); t2 appears ONLY in the final
        // array AND carries no function.name. With a delta already open, the old
        // `gapFill || name.isNotEmpty()` guard matched no branch and dropped t2 while the turn still
        // reported tool_use — it must be surfaced under the "tool" fallback instead (finding 5a).
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"t1","function":{"name":"first","arguments":"{}"}}]}}]}""",
                ),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"first","arguments":"{}"}},""" +
                        """{"id":"t2","type":"function","function":{"arguments":"{\"y\":2}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        // t1 opened once (echo suppressed); t2 surfaced under the "tool" fallback with its args.
        assertEquals(listOf("t1" to "first", "t2" to "tool"), sink.toolOpens)
        assertEquals(1, sink.calls.count { it == "openTool:tool" })
        assertTrue(sink.calls.contains("json:{\"y\":2}"))
    }

    @Test
    fun `stream args cut short are completed from the final copy, so the client gets the whole input`() = runTest {
        // The stream under-delivers a call's arguments (partial JSON) and the trailing consolidated message echoes the
        // SAME id with the complete arguments. The echo is not a second call, and the rest of its arguments is sent,
        // so the turn succeeds with valid tool input instead of dispatching truncated JSON.
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"t1","function":{"name":"run","arguments":"{\"x\":"}}]}}]}""",
                ),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("t1" to "run"), sink.toolOpens)
        val sent = sink.calls.filter { it.startsWith("json:") }.joinToString("") { it.removePrefix("json:") }
        assertEquals("{\"x\":1}", sent)
    }

    @Test
    fun `a call streamed without an id and echoed with one is one tool_use, not two`() = runTest {
        // Some vendors stream a tool call with no id and repeat it WITH an id in the final message. The echo is
        // matched back to the streamed call by its position and name, so the next turn's tool_result pairs with the
        // one tool_use the client was given.
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"function":{"name":"run","arguments":"{\"x\":1}"}}]}}]}""",
                ),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"call_7","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(1, sink.toolOpens.size, sink.toolOpens.toString())
        assertEquals(listOf("json:{\"x\":1}"), sink.calls.filter { it.startsWith("json:") })
    }
}
