// PORT-OF: the stream-machine pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline at unit
// level — multipart one-block joins, lazy/eager opens, args routing, failure capture with
// continued reading, truncated vs client-gone, watchdog outcome, harvest merge, replay
// in-position, usage extraction.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.WatchdogFired
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

private fun ctx(
    compact: Boolean = false,
    emit: Boolean = false,
    clientGone: Boolean = false,
    fired: WatchdogFired? = null,
    collect: Boolean = false,
    dedupe: Boolean = false,
) = StreamTurnContext(
    compact = compact,
    emitEncryptedReasoning = EmitEncryptedReasoning(emit),
    encodeReasoningEnvelope = { "env:" + it["id"]?.toString().orEmpty() },
    clientGone = { clientGone },
    watchdogFired = { fired },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    collectReasoningEnvelopes = collect,
    dedupeRepeatedSummaryParts = dedupe,
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}""",
)

class ResponsesStreamTranslatorTest {

    @Test
    fun `a completed response beats a late watchdog fire - Success not Failure`() = runTest {
        // The watchdog can trip while the reader is suspended on socket-EOF AFTER response.completed
        // was parsed. A fully-received turn must NOT be discarded as OVERLOADED (that retries a
        // successful compaction — the quota waste the watchdog exists to prevent).
        val outcome = ResponsesStreamTranslator(
            ctx(fired = WatchdogFired.Idle(idleMs = 200_000, sawFirstByte = true)),
        ).driveTurn(listOf(completed).asFlow(), RecordingSink())
        val success = outcome as TurnOutcome.Success
        assertEquals(100, success.usage.inputTokens)
    }

    @Test
    fun `multipart reasoning joins into ONE thinking block with paragraph breaks`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_part.added","output_index":0}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"part one"}"""),
                ev("""{"type":"response.reasoning_summary_part.done","output_index":0}"""),
                ev("""{"type":"response.reasoning_summary_part.added","output_index":0}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"part two"}"""),
                ev("""{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning"}}"""),
                completed,
            ).asFlow(),
            sink,
        )
        val success = outcome as TurnOutcome.Success
        assertEquals("part one\n\npart two", success.thinkingText)
        // exactly ONE thinking block opened; part boundaries are deltas, not closes
        assertEquals(1, sink.calls.count { it.startsWith("openThinking") })
        assertEquals(1, sink.calls.count { it.startsWith("close#") })
        assertTrue(sink.calls.contains("think#0:\n\n"))
    }

    // sequential_cutoff (A): each NEW reasoning item restates the running summary as a leading
    // prefix, then appends one new part. The recap MUST be suppressed — emitting it re-sends every
    // prior line per item, the duplication staircase (2026-07-23). Parts are >= the dedup min so
    // the quirk engages; the quirk is on for codex via summaryDelivery="sequential_cutoff".
    @Test
    fun `cross-item summary recap is suppressed - each part reaches the wire exactly once`() = runTest {
        val p0 = "Planning the prioritized review findings"
        val p1 = "Identifying stale plan conflicts and missing files"
        val p2 = "Highlighting certification gaps across the runtime"
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx(dedupe = true)).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$p0"}"""),
                // item 1 recaps p0, then adds p1
                ev("""{"type":"response.output_item.added","output_index":1,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":1,"delta":"$p0"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":1,"delta":"$p1"}"""),
                // item 2 recaps p0 + p1, then adds p2
                ev("""{"type":"response.output_item.added","output_index":2,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":2,"delta":"$p0"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":2,"delta":"$p1"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":2,"delta":"$p2"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        // Every summary part reaches the wire exactly once — no restatement staircase.
        assertEquals(1, sink.calls.count { it.contains(p0) }, "p0 duplicated: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(p1) }, "p1 duplicated")
        assertEquals(1, sink.calls.count { it.contains(p2) }, "p2 duplicated")
    }

    // Regression guard (2026-07-20): a paragraph two DISTINCT items genuinely share byte-for-byte is
    // NOT a leading recap of item 2 (item 2's own new part comes first), so it must be KEPT — a
    // turn-global seen-set wrongly cross-dropped it, causing summary starvation.
    @Test
    fun `a paragraph two distinct items coincidentally share is kept, not cross-dropped`() = runTest {
        val p0 = "Weighing the two candidate migration designs"
        val shared = "The token write must precede the navigation step"
        val fresh = "Now reconciling the session store mismatch cleanly"
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx(dedupe = true)).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$p0"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$shared"}"""),
                // item 1: a genuinely-new leading part (NOT a recap of p0), then the same shared paragraph
                ev("""{"type":"response.output_item.added","output_index":1,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":1,"delta":"$fresh"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":1,"delta":"$shared"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, sink.calls.count { it.contains(p0) })
        assertEquals(1, sink.calls.count { it.contains(fresh) })
        // Kept in BOTH items — the coincidence is not a leading cross-item recap.
        assertEquals(2, sink.calls.count { it.contains(shared) }, "shared paragraph wrongly dropped: ${sink.calls}")
    }

    // Regression guard (openai/codex#16801, live 2026-07-19): a part restated WITHIN one item (an
    // exact re-arrival at the same output_index) is suppressed, without affecting other items.
    @Test
    fun `an exact within-item part repeat is suppressed`() = runTest {
        val q0 = "Deferring the atomic publication ordering decision"
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx(dedupe = true)).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$q0"}"""),
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$q0"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, sink.calls.count { it.contains(q0) }, "within-item repeat leaked: ${sink.calls}")
    }

    @Test
    fun `tool flow - eager open on item added, args stream to same index, done closes`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"toolu_1","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"c\":"}"""),
                ev("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"1}"}"""),
                ev("""{"type":"response.function_call_arguments.done","output_index":1}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(
            listOf("openTool#0(toolu_1,run)", "json#0:{\"c\":", "json#0:1}", "close#0", "closeAll"),
            sink.calls,
        )
    }

    @Test
    fun `done-only tool arguments (no deltas) are emitted once before close`() = runTest {
        // Small tools can arrive with the COMPLETE arguments only on .done and no delta frames —
        // the !sawDelta branch must harvest them once so the client does not get input {}.
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"t1","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{\"x\":1}"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("openTool#0(t1,run)", "json#0:{\"x\":1}", "close#0", "closeAll"), sink.calls)
    }

    @Test
    fun `done arguments are NOT re-appended when deltas already streamed them`() = runTest {
        // .done repeats the consolidated arguments; having streamed the fragments (sawDelta), the
        // translator must NOT append the whole JSON again (that would corrupt the tool input).
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"t2","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"y\":"}"""),
                ev("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"2}"}"""),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{\"y\":2}"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        // Only the two streamed fragments — the consolidated {"y":2} on .done is NOT re-emitted.
        assertEquals(
            listOf("openTool#0(t2,run)", "json#0:{\"y\":", "json#0:2}", "close#0", "closeAll"),
            sink.calls,
        )
    }

    @Test
    fun `text opens lazily on first delta and usage lands`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}"""),
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"hel"}"""),
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"lo"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        val success = outcome as TurnOutcome.Success
        assertEquals("hello", success.bodyText)
        assertTrue(success.emittedText)
        assertEquals(100, success.usage.inputTokens)
        assertEquals(7, success.usage.outputTokens)
        assertEquals("openText#0", sink.calls.first())
    }

    @Test
    fun `failure event is captured, reading continues, outcome is classified`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed",
                       "response":{"error":{"code":"overloaded","message":"too many tokens"}}}""",
                ),
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"still read"}"""),
                completed,
            ).asFlow(),
            sink,
        )
        val failure = outcome as TurnOutcome.Failure
        // overflow classification survives via the SAME classifier (v29 P0 fix)
        assertEquals(ErrorType.INVALID_REQUEST, failure.type)
        assertTrue(failure.message.contains("prompt is too long"))
        assertTrue(sink.calls.contains("text#0:still read"))
    }

    @Test
    fun `truncated stream without terminal is an honest overloaded failure`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""),
            ).asFlow(),
            RecordingSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failure.type)
        assertTrue(failure.message.contains("truncated"))
    }

    @Test
    fun `client gone without terminal is ClientAbandoned - never an error frame`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx(clientGone = true)).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"x"}"""),
            ).asFlow(),
            RecordingSink(),
        )
        assertEquals(TurnOutcome.ClientAbandoned, outcome)
    }

    @Test
    fun `watchdog fired maps to overloaded with the idle cap message`() = runTest {
        val outcome = ResponsesStreamTranslator(
            ctx(fired = WatchdogFired.Idle(idleMs = 200_000, sawFirstByte = true)),
        ).driveTurn(emptyList<JsonObject>().asFlow(), RecordingSink())
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failure.type)
        assertTrue(failure.message.contains("180s idle cap"))
    }

    @Test
    fun `harvest fallback fills sparse deltas from the terminal object`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.completed","response":{"id":"r1","output":[
                        {"type":"reasoning","summary":[{"type":"summary_text","text":"deep thought"}]},
                        {"type":"message","content":[{"type":"output_text","text":"harvested body"}]}
                    ],"usage":{"input_tokens":5,"output_tokens":2}}}""",
                ),
            ).asFlow(),
            sink,
        )
        val success = outcome as TurnOutcome.Success
        assertEquals("harvested body", success.bodyText)
        assertEquals("deep thought", success.thinkingText)
        // harvest fills BUFFERS only; no wire blocks were opened for them
        assertEquals(listOf("closeAll"), sink.calls)
    }

    @Test
    fun `replay emits redacted thinking in position when gated on`() = runTest {
        val sink = RecordingSink()
        ResponsesStreamTranslator(ctx(emit = true)).driveTurn(
            listOf(
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"sum"}"""),
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"t1","name":"run"}}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        val redactedAt = sink.calls.indexOfFirst { it.startsWith("redacted:") }
        val toolAt = sink.calls.indexOfFirst { it.startsWith("openTool") }
        assertTrue(
            redactedAt in 1 until toolAt,
            "replay block must land after summary close, before tool: ${sink.calls}",
        )
    }

    @Test
    fun `fold-eligible turn collects reasoning envelopes and reasoning_tokens`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx(collect = true)).driveTurn(
            listOf(
                ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"deep"}"""),
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.completed","response":{"usage":{"input_tokens":100,
                       "output_tokens":600,"output_tokens_details":{"reasoning_tokens":516}}}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertEquals(516, success.usage.reasoningTokens)
        assertEquals(1, success.reasoningEnvelopes.size)
        assertTrue(success.reasoningEnvelopes.first().contains("rs_1"))
    }

    @Test
    fun `non-fold turn never collects envelopes - reasoningEnvelopes stays empty (parity)`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx(collect = false)).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertTrue((outcome as TurnOutcome.Success).reasoningEnvelopes.isEmpty())
    }

    @Test
    fun `replay stays off on compact turns even when enabled`() = runTest {
        val sink = RecordingSink()
        ResponsesStreamTranslator(ctx(emit = true, compact = true)).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(sink.calls.none { it.startsWith("redacted:") })
    }
}
