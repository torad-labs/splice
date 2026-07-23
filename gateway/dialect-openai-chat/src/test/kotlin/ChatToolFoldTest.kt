// NEW (final review 2026-07-23): the final-message tool-fold cases, split out of
// ChatStreamTranslatorTest (which hit the detekt LargeClass ceiling). Covers finding 3 (name+args
// both final-only open with real input), finding 5a (a nameless final-only call is surfaced, not
// dropped), and PINS the two documented known limitations — finding 4 (id-less stream echoed with an
// id duplicates) and finding 5b (an id-matched echo does not repair under-delivered stream args).
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
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun foldEv(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun foldCtx() = ChatTurnContext({ false }, { null }, 180_000, 900_000)

class ChatToolFoldTest {

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
    fun `id-less streamed call echoed with an id duplicates - pins known limitation finding 4`() = runTest {
        // KNOWN LIMITATION (pinned): a call STREAMED without an id gets a synth "toolu_<n>" slot;
        // the trailing consolidated message echoes the SAME call but now WITH a real id. That id is
        // not in openedToolIds and cannot be matched back to the synth slot, so the echo mints a
        // SECOND tool_use. Left as-is deliberately — a name+args suppressor would risk dropping a
        // legitimate distinct call. Reachable only via non-standard vendors (not codex/grok/kimi).
        val sink = FoldRec()
        val outcome = ChatStreamTranslator(foldCtx()).driveTurn(
            listOf(
                foldEv(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"function":{"name":"run","arguments":"{\"x\":1}"}}]}}]}""",
                ),
                foldEv(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"call_real","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        // The duplicate is the pinned (known-wrong) behavior: synth-id open, then a second real-id open.
        assertEquals(listOf("toolu_1000000" to "run", "call_real" to "run"), sink.toolOpens)
        assertEquals(2, sink.calls.count { it == "openTool:run" })
    }

    @Test
    fun `id-matched echo does not repair under-delivered stream args - pins known limitation finding 5b`() = runTest {
        // KNOWN LIMITATION (pinned): the stream under-delivers a call's arguments (partial JSON),
        // then the trailing consolidated message echoes the SAME id with the COMPLETE arguments. The
        // echo is suppressed wholesale (by id) to keep the common full-delivery echo a no-op, so the
        // final's complete copy is discarded and the wire keeps only the partial args. Left as-is —
        // repairing it needs per-block arg tracking, not worth the hot-path risk for a non-standard
        // vendor (not codex/grok/kimi).
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
        // only the partial streamed args reached the wire; the final's complete "{\"x\":1}" is dropped
        assertEquals(listOf("json:{\"x\":"), sink.calls.filter { it.startsWith("json:") })
    }
}
