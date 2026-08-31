// PORT-OF: the stream-machine pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline at unit
// level — multipart one-block joins, lazy/eager opens, args routing, failure capture with
// continued reading, truncated vs client-gone, watchdog outcome, harvest merge, replay
// in-position, usage extraction.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.BufferCapacity
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
    shared: SharedSummaryParts = SharedSummaryParts(),
    capture: ((List<String>, List<String>) -> Unit)? = null,
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
    summaryPartsShared = shared,
    onTurnReasoning = capture ?: { _, _ -> },
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

    // sequential_cutoff rendering (codex-parity port 2026-08-26) lives in
    // SequentialCutoffRenderTest — in that mode summary DELTAS are dropped entirely and the
    // reasoning_summary_text.done events of the ACTIVE item are the render surface. The three
    // delta-path dedup pins that lived here died with the delta path.

    // Cross-TURN recap suppression (2026-08-26) lives in SummaryDedupCrossTurnTest — this class
    // sits at detekt's LargeClass ceiling.

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
                ev(
                    """{"type":"response.output_item.done","output_index":1,
                       "item":{"type":"function_call"}}""",
                ),
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
    fun `a null call_id falls back to id and a null name opens with an empty name`() = runTest {
        // The Responses call_id -> id -> synthetic chain is distinct from the Chat null-id path.
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":0,
                       "item":{"type":"function_call","call_id":null,"id":"resp_abc","name":null}}""",
                ),
                ev("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"a\":1}"}"""),
                ev("""{"type":"response.function_call_arguments.done","output_index":0}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        // call_id JsonNull -> id fallback; name JsonNull -> empty (strOrEmpty filters both).
        assertEquals("openTool#0(resp_abc,)", sink.calls.first())
        assertFalse(sink.calls.any { it.contains("null") }, "literal null must never reach the wire: ${sink.calls}")
        assertTrue(sink.calls.contains("json#0:{\"a\":1}"), "args must route to the resolved tool block")
    }

    @Test
    fun `both call_id and id null get a nonempty synthetic id, never the literal null`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":2,
                       "item":{"type":"function_call","call_id":null,"id":null,"name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{}"}"""),
                ev("""{"type":"response.function_call_arguments.done","output_index":2}"""),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        val open = sink.calls.first { it.startsWith("openTool") }
        assertTrue(open.startsWith("openTool#0(toolu_synth"), "expected a synthetic id: $open")
        assertFalse(open.contains("null"), "synthetic id must be nonempty, never \"null\": $open")
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

    // L3 honesty hole (BS-1): a content-filtered response.incomplete must not masquerade as a
    // clean max_tokens stop — mirrors ChatStreamTranslator's contentFiltered branch (line 97-105).
    @Test
    fun `response incomplete with reason content_filter is an honest failure, not a clean stop`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""),
                ev(
                    """{"type":"response.incomplete","response":{"status":"incomplete",
                       "incomplete_details":{"reason":"content_filter"}}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.providerReported)
        assertTrue(failure.message.contains("content filter"))
    }

    @Test
    fun `response incomplete with reason max_output_tokens stays a clean incomplete success`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""),
                ev(
                    """{"type":"response.incomplete","response":{"status":"incomplete",
                       "incomplete_details":{"reason":"max_output_tokens"}}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertTrue(success.incomplete)
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

// RC-1 walls (reasoning-cache campaign, 2026-07-24): the capture sink fires exactly when a
// successful tool-use round produced envelopes keyed by REAL upstream ids — synthetic ids and
// non-tool rounds never seed the cache.
class TurnReasoningCaptureTest {

    @Test
    fun `a successful tool round with envelopes fires the capture with real ids`() = runTest {
        var captured: Pair<List<String>, List<String>>? = null
        val outcome = ResponsesStreamTranslator(
            ctx(collect = true, capture = { ids, envs -> captured = ids to envs }),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                ev(
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,""" +
                        """"item":{"type":"function_call","call_id":"call_abc","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("call_abc"), captured?.first)
        assertEquals(1, captured?.second?.size)
    }

    @Test
    fun `the captured envelope carries the exact encrypted content, not just an id-derived value`() = runTest {
        // review 2026-07-24 (thread on :529): size==1 stayed green if encrypted_content was
        // dropped or mutated — the wall must assert the full payload through a content-aware encoder
        var captured: List<String>? = null
        val base = ctx(collect = true, capture = { _, envs -> captured = envs })
        ResponsesStreamTranslator(
            base.copy(
                encodeReasoningEnvelope = { item ->
                    val id = item["id"]?.jsonPrimitive?.content.orEmpty()
                    val cipher = item["encrypted_content"]?.jsonPrimitive?.content.orEmpty()
                    "$id|$cipher"
                },
            ),
        ).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_abc","encrypted_content":"cipher-sentinel-9f2"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,""" +
                        """"item":{"type":"function_call","call_id":"call_abc","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertEquals(listOf("rs_abc|cipher-sentinel-9f2"), captured)
    }

    @Test
    fun `a failed terminal after reasoning and a real call id never seeds the capture`() = runTest {
        // review 2026-07-24: the capture must stay gated on the SUCCESS terminal — a regression
        // moving onTurnReasoning ahead of terminal classification would reinject a rejected turn
        var fired = false
        val outcome = ResponsesStreamTranslator(
            ctx(collect = true, capture = { _, _ -> fired = true }),
        ).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,""" +
                        """"item":{"type":"function_call","call_id":"call_real","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}"""),
                ev("""{"type":"response.failed","response":{"error":{"message":"upstream rejected"}}}"""),
            ).asFlow(),
            RecordingSink(),
        )
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue(!fired, "a rejected turn must not seed the cache")
    }

    @Test
    fun `a truncated stream (EOF without terminal) never seeds the capture`() = runTest {
        var fired = false
        val outcome = ResponsesStreamTranslator(
            ctx(collect = true, capture = { _, _ -> fired = true }),
        ).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,""" +
                        """"item":{"type":"function_call","call_id":"call_real","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}"""),
                // stream closes here without response.completed
            ).asFlow(),
            RecordingSink(),
        )
        assertTrue(outcome !is TurnOutcome.Success)
        assertTrue(!fired, "a truncated turn must not seed the cache")
    }

    @Test
    fun `a synthetic-id tool round never seeds the cache`() = runTest {
        var fired = false
        ResponsesStreamTranslator(
            ctx(collect = true, capture = { _, _ -> fired = true }),
        ).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                ev(
                    """{"type":"response.output_item.added","output_index":1,""" +
                        """"item":{"type":"function_call","name":"run"}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertFalse(fired, "toolu_synth ids repeat across turns — they must never key the cache")
    }

    @Test
    fun `a text-only round never fires the capture`() = runTest {
        var fired = false
        ResponsesStreamTranslator(
            ctx(collect = true, capture = { _, _ -> fired = true }),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"hello"}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertFalse(fired)
    }
}

// Tool-search capture walls (ToolSurface deferral, the search round). A tool_search_call opens NO
// wire block and must never touch hasToolUse/turnToolIds — a synthetic/foreign id there would
// mis-key the reasoning cache (a known prior bug class this pins against).
class ToolSearchCaptureTest {

    @Test
    fun `a tool_search_call opens no block, leaves hasToolUse false, lands in toolSearches`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"ts_1","execution":"client","arguments":{"query":"web search exa"}}}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        val success = outcome as TurnOutcome.Success
        assertFalse(success.hasToolUse)
        val opened = setOf("openTool", "openText", "openThinking")
        assertTrue(sink.calls.none { call -> opened.any { call.startsWith(it) } }, "no wire block opens")
        assertEquals(1, success.toolSearches.size)
        assertEquals("ts_1", success.toolSearches.single().callId.v)
        assertEquals("web search exa", success.toolSearches.single().query)
    }

    @Test
    fun `execution server is not captured`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"ts_1","execution":"server","arguments":{"query":"x"}}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertTrue(success.toolSearches.isEmpty())
    }

    @Test
    fun `arguments as a JSON string parses`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"ts_1","execution":"client",""" +
                        """"arguments":"{\"query\":\"exa search\",\"limit\":3}"}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertEquals("exa search", success.toolSearches.single().query)
        assertEquals(3, success.toolSearches.single().limit)
    }

    @Test
    fun `malformed arguments yields empty query and does not throw`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"ts_1","execution":"client","arguments":"not valid json {"}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertEquals(1, success.toolSearches.size)
        assertEquals("", success.toolSearches.single().query)
    }

    @Test
    fun `empty call_id is dropped`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"","execution":"client","arguments":{"query":"x"}}}""",
                ),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertTrue(success.toolSearches.isEmpty())
    }

    @Test
    fun `terminal-only delivery is recovered by the harvest fallback`() = runTest {
        // No output_item.done for the search call — only the terminal response object carries it.
        val terminal = ev(
            """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":10,"output_tokens":1},
                "output":[{"type":"tool_search_call","call_id":"ts_9","execution":"client",
                    "arguments":{"query":"harvested"}}]}}""",
        )
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(listOf(terminal).asFlow(), RecordingSink())
        val success = outcome as TurnOutcome.Success
        assertEquals(1, success.toolSearches.size)
        assertEquals("ts_9", success.toolSearches.single().callId.v)
    }

    @Test
    fun `streamed capture present - the harvest fallback does not duplicate`() = runTest {
        val terminal = ev(
            """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":10,"output_tokens":1},
                "output":[{"type":"tool_search_call","call_id":"ts_1","execution":"client",
                    "arguments":{"query":"streamed"}}]}}""",
        )
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call",""" +
                        """"call_id":"ts_1","execution":"client","arguments":{"query":"streamed"}}}""",
                ),
                terminal,
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertEquals(1, success.toolSearches.size, "the streamed capture wins; the harvest never re-adds it")
    }

    // The dispatch-after-search wall: a function_call for a tool absent from THIS turn's
    // declared set (i.e. a tool the model only learned about via a prior search) still opens an
    // ordinary tool_use block — splice never validates names against the declared set.
    @Test
    fun `a function_call for an undeclared tool still opens a tool_use block`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":0,""" +
                        """"item":{"type":"function_call","call_id":"call_1","name":"mcp__exa__web_search_exa"}}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        val success = outcome as TurnOutcome.Success
        assertTrue(success.hasToolUse)
        assertTrue(sink.calls.any { it.startsWith("openTool") && it.contains("mcp__exa__web_search_exa") })
    }
}

// NF-06 lives in its own class: ResponsesStreamTranslatorTest sits at detekt's LargeClass ceiling.
class ResponsesRunawayGuardTest {

    private fun toolAdded(outputIndex: Int): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("response.output_item.added"))
        put("output_index", JsonPrimitive(outputIndex))
        put(
            "item",
            buildJsonObject {
                put("type", JsonPrimitive("function_call"))
                put("call_id", JsonPrimitive("call_$outputIndex"))
                put("name", JsonPrimitive("run"))
            },
        )
    }

    private fun toolArgs(outputIndex: Int, delta: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("response.function_call_arguments.delta"))
        put("output_index", JsonPrimitive(outputIndex))
        put("delta", JsonPrimitive(delta))
    }

    @Test
    fun `retained tool block count trips the shared capacity guard`() = runTest {
        val sink = RecordingSink()
        var emitted = 0
        val events = kotlinx.coroutines.flow.flow {
            repeat(BufferCapacity.MAX_TOOL_INDEX_ENTRIES + 10) { outputIndex ->
                emitted += 1
                emit(toolAdded(outputIndex))
            }
        }

        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink)
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        assertEquals(BufferCapacity.MAX_TOOL_INDEX_ENTRIES, sink.calls.count { it.startsWith("openTool") })
        assertEquals(BufferCapacity.MAX_TOOL_INDEX_ENTRIES + 1, emitted, "guard must stop collection")
    }

    @Test
    fun `aggregate arguments across open tool blocks trip the shared capacity guard`() = runTest {
        val chunk = "x".repeat(1_000_000)
        val sink = RecordingSink()
        val events = kotlinx.coroutines.flow.flow {
            repeat(25) { outputIndex ->
                emit(toolAdded(outputIndex))
                emit(toolArgs(outputIndex, chunk))
            }
        }

        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink)
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        assertEquals(20, sink.calls.count { it.startsWith("json#") })
    }

    @Test
    fun `runaway upstream trips the shared buffer cap into an honest local failure - NF-06`() = runTest {
        // 25 x 1M-char text deltas, never a response.completed — the misbehaving-upstream shape.
        // The guard must latch at the shared cap, stop feeding the buffers, and end the turn as a
        // non-provider-reported API_ERROR (never a crash, never a provider attribution).
        val chunk = "x".repeat(1_000_000)
        val sink = RecordingSink()
        var emitted = 0
        val events = kotlinx.coroutines.flow.flow {
            repeat(25) {
                emitted += 1
                emit(
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("response.output_text.delta"))
                        put("output_index", kotlinx.serialization.json.JsonPrimitive(0))
                        put("delta", kotlinx.serialization.json.JsonPrimitive(chunk))
                    },
                )
            }
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink)
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertFalse(failure.providerReported, "the runaway verdict is LOCAL — never provider-attributed")
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        // consumption stopped at the latch: 20 x 1M reaches the cap, later deltas never emit
        val deltas = sink.calls.count { it.startsWith("text#") }
        assertTrue(deltas in 20..21, "expected the guard to stop the stream at the cap, saw $deltas deltas")
        assertTrue(emitted < 25, "the guard must unwind the upstream, not drain it; emitted=$emitted")
    }
}

// CX-01 in its own class: ResponsesStreamTranslatorTest sits at detekt's LargeClass ceiling.
class ResponsesToolArgsValidationTest {
    @Test
    fun `truncated tool arguments plus a terminal is a Failure, not corrupt Success - CX-01`() = runTest {
        // .done arrives with a mid-string-truncated buffer; pre-fix the block closed as Success.
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"toolu_1","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"c\":"}"""),
                ev("""{"type":"response.function_call_arguments.done","output_index":1}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.message.contains("malformed JSON"), failure.message)
    }

    @Test
    fun `an opened tool with zero argument deltas is a Failure - CX-01`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.added","output_index":1,
                       "item":{"type":"function_call","call_id":"toolu_1","name":"run"}}""",
                ),
                ev("""{"type":"response.function_call_arguments.done","output_index":1}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertTrue((outcome as TurnOutcome.Failure).message.contains("empty arguments"), outcome.message)
    }

    @Test
    fun `valid tool arguments still succeed - CX-01`() = runTest {
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
            RecordingSink(),
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
    }
}

// W4-A (WIDENED past the item's two named dialects): openai-responses carries the SAME hole. Its
// contentFiltered gate only fires on `status: incomplete`, but an OpenAI refusal arrives with
// `status: completed`, and both refusal carriers fell into `else -> Unit` — so a refused turn ended
// `finished = true` with zero text. This dialect serves 2 of the 4 providers (codex + grok).
class ResponsesRefusalHonestyTest {
    // Round-2 review (UNVERIFIED_CLAIM): the artifact claimed the refusal-vs-content_filter
    // co-occurrence was "pinned by test in BOTH dialects". It was pinned in chat only; a mutation
    // reversing the elvis at ResponsesStreamTranslator.kt:232 stayed green here. This is the twin
    // of chat's `a refusal on the content_filter frame carries the model's words` test. The
    // model's stated reason IS the verdict — the generic content-filter phrase discards it.
    @Test
    fun `a refusal that also trips the content filter carries the model's words - CX-08 ranking`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":"Refusing: policy."}"""),
                ev(
                    """{"type":"response.incomplete","response":{"id":"r1",
                       "incomplete_details":{"reason":"content_filter"}}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.providerReported)
        assertTrue(f.message.contains("Refusing: policy."), f.message)
        assertTrue(!f.message.contains("stopped by content filter"), f.message)
        assertEquals(null, f.partial)
    }

    @Test
    fun `a streamed refusal delta is a provider-reported failure, not a clean completed turn`() = runTest {
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":"I won't "}"""),
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":"do that."}"""),
                completed,
            ).asFlow(),
            sink,
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.providerReported, "the BACKEND sent the refusal — G20 provenance is upstream")
        assertTrue(failure.message.contains("I won't do that."), failure.message)
        // A refusal is deterministic: no salvage may ride it, or the re-anchor loop re-POSTs it.
        assertEquals(null, failure.partial)
    }

    // The second carrier: a `refusal`-typed content part on the completed response's output. This
    // is the only one a backend that streams no refusal deltas uses.
    @Test
    fun `a refusal content part on the completed response fails the turn`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"refusal",
                       "refusal":"Refusing: policy."}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.providerReported)
        assertTrue(failure.message.contains("Refusing: policy."), failure.message)
    }

    @Test
    fun `the streamed and completed carriers of one refusal do not double-append`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":"Nope."}"""),
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"refusal","refusal":"Nope."}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(1, Regex("Nope\\.").findAll(failure.message).count(), failure.message)
    }

    // must_not: an empty refusal part, and an ordinary text turn, must be completely unaffected.
    @Test
    fun `an empty refusal part leaves an ordinary completed turn a Success`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"hello"}"""),
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"output_text","text":"hello"},
                       {"type":"refusal","refusal":""}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val success = outcome as TurnOutcome.Success
        assertEquals("hello", success.bodyText)
    }

    // ---- repair round 2 -------------------------------------------------------------------
    // Same two axes as chat (TYPE of the carrier, ACCUMULATION over the incremental one), plus the
    // carrier this dialect was missing outright.

    // TYPE AXIS. ResponseOutputRefusal.refusal and ResponseRefusalDoneEvent.refusal are typed
    // `string`; a boolean/number is a vendor flag, and strOrEmpty turned `false` into the non-blank
    // string "false" — failing a working turn and blaming the backend for it (G20 inverted).
    @Test
    fun `a boolean refusal delta riding a working stream is not a refusal`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":false}"""),
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"hello"}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        assertEquals("hello", (outcome as TurnOutcome.Success).bodyText)
    }

    @Test
    fun `a boolean refusal content part beside real output is not a refusal`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"output_text","text":"hello"},
                       {"type":"refusal","refusal":false}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        assertEquals("hello", (outcome as TurnOutcome.Success).bodyText)
    }

    // ACCUMULATION AXIS. onTextDelta appends verbatim in the very same reducer; only the refusal
    // arm deduped, so a token-streamed refusal lost every repeated token.
    @Test
    fun `a token-streamed refusal keeps every word, repeats included`() = runTest {
        val tokens = listOf(
            "I", " won", "'t", " do", " that", ".",
            " I", " won", "'t", " do", " anything", " illegal", ".",
        )
        val frames = tokens.map {
            ev("""{"type":"response.refusal.delta","output_index":0,"delta":"$it"}""")
        } + completed
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(frames.asFlow(), RecordingSink())
        val f = outcome as TurnOutcome.Failure
        assertTrue(f.message.contains("I won't do that. I won't do anything illegal."), f.message)
    }

    // THE MISSING CARRIER. ResponseRefusalDoneEvent ("emitted when refusal text is finalized")
    // carries the COMPLETE refusal string, and the documented order is refusal.delta xN ->
    // refusal.done -> content_part.done -> output_item.done. A backend that finalizes without
    // streaming deltas reached the client as a clean, zero-text Success.
    @Test
    fun `a refusal delivered only on response refusal done fails the turn`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.done","output_index":0,"refusal":"I won't do that."}"""),
                completed,
            ).asFlow(),
            RecordingSink(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.providerReported, "the BACKEND finalized the refusal — G20 provenance is upstream")
        assertTrue(f.message.contains("I won't do that."), f.message)
        assertEquals(null, f.partial)
    }

    // Three carriers, one refusal: the guard that dropping the dedup did not regress into
    // duplication. delta-wins means the two whole-copy carriers are both no-ops here.
    @Test
    fun `all three carriers of one refusal appear exactly once`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.refusal.delta","output_index":0,"delta":"Nope."}"""),
                ev("""{"type":"response.refusal.done","output_index":0,"refusal":"Nope."}"""),
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"refusal","refusal":"Nope."}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(1, Regex("Nope\\.").findAll(f.message).count(), f.message)
    }

    // A NON-EMPTY refusal part beside real prose. HEAD returned Success with the text; failing the
    // turn is correct under L3 and matches the pre-existing contentFiltered shape — pin the
    // deliberate choice so it cannot silently reverse. (The EMPTY-part control above is its twin.)
    @Test
    fun `a non-empty refusal part beside real prose fails the turn`() = runTest {
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"here is some prose"}"""),
                ev(
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",
                       "output":[{"type":"message","content":[{"type":"output_text","text":"prose"},
                       {"type":"refusal","refusal":"but I stop here"}]}]}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.providerReported)
        assertTrue(f.message.contains("but I stop here"), f.message)
        assertEquals(null, f.partial)
    }
}
