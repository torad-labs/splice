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
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughQuirksDefaults
import splice.dialect.passthrough.PassthroughStreamTranslator
import splice.dialect.passthrough.PassthroughTurnContext
import splice.spi.BufferCapacity
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
    override suspend fun addRedactedThinking(data: String) { calls.add("redacted:$data") }
    override suspend fun openRawBlock(contentBlock: JsonObject) =
        WireBlockIndex(n++).also { calls.add("rawOpen:$contentBlock") }
    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) { calls.add("rawDelta:$delta") }
}

private val KIMI = PassthroughQuirksDefaults().kimi("kimi")

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun ctx() = PassthroughTurnContext({ false }, { null }, 180_000, 900_000)

private suspend fun drive(sink: Rec, vararg evs: JsonObject): TurnOutcome =
    PassthroughStreamTranslator(ctx(), KIMI).driveTurn(evs.toList().asFlow(), sink)

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

    // DR-142 (dialect sweep, 2026-08-31): applyDelta dispatched on the DELTA type and never checked
    // the KIND of the block it targeted — DR-108's exact defect, fixed in the Responses dialect and
    // never swept in the passthrough twin, which is the dialect whose whole job is re-indexing
    // arbitrary upstream blocks onto the client wire. A text_delta aimed at an open tool_use block
    // was forwarded verbatim (WireBlockWriter guards openness, not kind), emitting text_delta INSIDE
    // tool_use on the wire and latching emittedText so TurnOutcome claimed text was delivered when
    // the bytes went into a tool block. The non-stream sibling CollectingBlocks type-checks every
    // one, so the SAME upstream frame was dropped on stream:false and forwarded corrupt on
    // stream:true. Every fixture in this file sends a delta matching the block it opened.
    @Test
    fun `a text delta aimed at an open tool block is dropped - DR-142`() = runTest {
        val sink = Rec()
        drive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev(
                """{"type":"content_block_start","index":0,""" +
                    """"content_block":{"type":"tool_use","id":"t1","name":"Read"}}""",
            ),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"LEAK-142"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertFalse(
            sink.calls.any { it.startsWith("text:") },
            "a text_delta must never enter a tool_use block: ${sink.calls}",
        )
        assertTrue(sink.calls.contains("json:{}"), "the tool's OWN delta must still forward: ${sink.calls}")
    }

    // DR-142 control: the guard must stay permissive where the protocol genuinely crosses kinds.
    // A thinking_delta into a text block is corrupt and dropped, but narrowing input_json_delta to
    // TOOL alone would break server_tool_use blocks, which stream their input the same way.
    @Test
    fun `a thinking delta aimed at an open text block is dropped - DR-142`() = runTest {
        val sink = Rec()
        drive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"LEAK-142"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"real"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertFalse(sink.calls.any { it.startsWith("think:") }, "thinking_delta into a text block: ${sink.calls}")
        assertTrue(sink.calls.contains("text:real"), "the text block's own delta must forward: ${sink.calls}")
    }

    @Test
    fun `full turn re-indexes blocks, synthesizes one signature, and normalizes usage`() = runTest {
        val sink = Rec()
        val outcome = PassthroughStreamTranslator(ctx(), KIMI).driveTurn(fullTurnEvents().asFlow(), sink)
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

    // DR-122: last member of the empty-delta-latch family (DR-75/CX-09 fixed text/thinking). An
    // EMPTY signature_delta must not latch signatureSeen: on the synthesizing profile a thinking
    // block closed with only an empty signature would otherwise skip synthesize-at-close, and
    // Claude Code silently discards the unsigned block — the exact regression synthesis prevents.
    @Test
    fun `an empty signature_delta does not suppress synthesis at close - DR-122`() = runTest {
        val sink = Rec()
        drive(
            sink,
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"t"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":""}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_stop"}"""),
        )
        // the empty delta still forwards verbatim; the synthetic one must ALSO arrive
        assertTrue(sink.calls.contains("sig:splice-synth-v1"), sink.calls.joinToString())
        assertTrue(sink.calls.indexOf("sig:splice-synth-v1") < sink.calls.indexOf("close"))
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

    // CX-18: Anthropic's newer per-TTL shape reports cache creation as a nested object instead of
    // the flat cache_creation_input_tokens. Reading only the flat key scored those tokens as zero,
    // and since successOutcome folds cacheCreation back into inputTokens, the whole context-window
    // percentage came out low on every cache-writing turn.
    @Test
    fun `nested cache_creation buckets sum into the input total`() = runTest {
        val sink = Rec()
        val s = drive(
            sink,
            ev(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":5,""" +
                    """"cache_creation":{"ephemeral_5m_input_tokens":30,"ephemeral_1h_input_tokens":7}}}}""",
            ),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertEquals(52, s.usage.inputTokens) // 10 + cache_read 5 + cache_creation (30 + 7)
        assertEquals(5, s.usage.cachedTokens)
    }

    @Test
    fun `the flat cache_creation key still wins over the nested object`() = runTest {
        val sink = Rec()
        val s = drive(
            sink,
            ev(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,""" +
                    """"cache_creation_input_tokens":4,"cache_creation":{"ephemeral_5m_input_tokens":999}}}}""",
            ),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertEquals(14, s.usage.inputTokens) // 10 + flat 4; the nested object is not double-counted
    }

    // CX-09 REGRESSION GUARD. emittedThinking must mean "the client received reasoning", not
    // "a thinking block was opened". Kimi can open a thinking block and close it having sent no
    // delta; if that counts as content, TurnPipeline's empty-turn gate short-circuits and a turn
    // carrying literally nothing ends as a clean terminal — the exact L3 violation CX-09 closed.
    // Caught in review 2026-08-11 after the first cut set the flag at openThinking.
    @Test
    fun `an opened but empty thinking block does not count as delivered content`() = runTest {
        val s = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":0}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertEquals("", s.thinkingText)
        assertFalse(s.emittedThinking, "an empty thinking block delivered nothing to the client")
    }

    @Test
    fun `a whitespace-only thinking delta does not count as delivered content`() = runTest {
        val s = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"   "}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":0}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertFalse(s.emittedThinking, "whitespace is not content")
    }

    @Test
    fun `real thinking content DOES count - the case CX-09 exists to protect`() = runTest {
        val s = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            ev(
                """{"type":"content_block_delta","index":0,""" +
                    """"delta":{"type":"thinking_delta","thinking":"real reasoning"}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertTrue(s.emittedThinking, "a kimi thinking-only turn must NOT be graded empty")
    }

    // DR-75 (fresh-eyes sweep): emittedText carried the exact defect CX-09 closed for thinking —
    // an EMPTY text delta latched it, so a turn that delivered zero text characters graded as
    // content-bearing and short-circuited the empty-turn honesty gate. Forwarding stays
    // unconditional (the kimi goldens pin append + sink order); only the flag is gated.
    @Test
    fun `an empty text delta does not count as delivered content - DR-75`() = runTest {
        val s = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":0}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertEquals("", s.bodyText)
        assertFalse(s.emittedText, "an empty text delta delivered nothing to the client")
    }

    @Test
    fun `real text content still counts as delivered - DR-75 control`() = runTest {
        val s = drive(
            Rec(),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        ) as TurnOutcome.Success
        assertTrue(s.emittedText)
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

    // PT-001 (review 2026-08-15): logging every dropped post-stop delta is unbounded — a chatty
    // misbehaving upstream sending many stray deltas for an already-closed index used to write one
    // daemon.log line PER delta. Latched like TurnDriver's malformedLogged (G9): still visible,
    // exactly once per turn.
    @Test
    fun `many post-stop deltas for the same dead index log exactly once`() = runTest {
        val logs = mutableListOf<String>()
        val ctx = PassthroughTurnContext({ false }, { null }, 180_000, 900_000, log = { logs.add(it) })
        val strayDeltas = List(20) {
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stray"}}""")
        }
        val events = listOf(
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
        ) + strayDeltas + listOf(ev("""{"type":"message_stop"}"""))
        val outcome = PassthroughStreamTranslator(ctx, KIMI).driveTurn(events.asFlow(), Rec())
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertEquals(1, logs.size, "20 post-stop deltas on one dead index must log once, not 20: $logs")
        assertTrue(logs.single().contains("unmapped index=0"), logs.single())
    }

    @Test
    fun `finished turn beats a late watchdog fire - success not overloaded`() = runTest {
        // Chat/Responses parity: message_stop already delivered, then poller fires on EOF wait.
        // Preferring watchdog discarded successful kimi turns and burned quota on retries.
        val late = PassthroughTurnContext(
            { false },
            { splice.spi.WatchdogFired.Idle(180_000, true) },
            180_000,
            900_000,
        )
        val sink = Rec()
        val outcome = PassthroughStreamTranslator(late, KIMI).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
                ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
                ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}"""),
                ev("""{"type":"content_block_stop","index":0}"""),
                ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
                ev("""{"type":"message_stop"}"""),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertEquals("done", (outcome as TurnOutcome.Success).bodyText)
    }

    @Test
    fun `runaway upstream trips the shared buffer cap into an honest local failure - NF-06`() = runTest {
        // 25 x 1M-char text deltas, never a message_stop — the misbehaving-upstream shape.
        val chunk = "x".repeat(1_000_000)
        val sink = Rec()
        val events = kotlinx.coroutines.flow.flow {
            emit(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
            repeat(25) {
                emit(
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("content_block_delta"))
                        put("index", kotlinx.serialization.json.JsonPrimitive(0))
                        put(
                            "delta",
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("text_delta"))
                                put("text", kotlinx.serialization.json.JsonPrimitive(chunk))
                            },
                        )
                    },
                )
            }
        }
        val outcome = PassthroughStreamTranslator(ctx(), KIMI).driveTurn(events, sink)
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertFalse(failure.providerReported, "the runaway verdict is LOCAL — never provider-attributed")
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        val deltas = sink.calls.count { it.startsWith("text:") }
        assertTrue(deltas in 20..21, "expected the guard to stop the stream at the cap, saw $deltas deltas")
    }
}

// CX-07 / W4-A: Anthropic ships SEVEN stop_reason values; onMessageDelta branched on TWO and sent
// the rest to `else -> Unit`, so a turn the BACKEND refused, paused, or could not fit in the model
// context window reached the client as a clean, complete Success. A NEW class rather than more
// @Test methods above: the class above is at detekt's LargeClass budget, and the file-private
// helpers (ev / Rec / drive) are visible here.
class PassthroughStopReasonHonestyTest {

    // A turn that produced prose and terminated properly, ending on the given stop_reason. Every
    // case below differs ONLY in that one value — the L3 verdict must come from it and nothing else.
    private suspend fun turnEndingWith(stopReason: String): TurnOutcome = drive(
        Rec(),
        ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
        ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}"""),
        ev("""{"type":"content_block_stop","index":0}"""),
        ev("""{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":3}}"""),
        ev("""{"type":"message_stop"}"""),
    )

    @Test
    fun `stop_reason refusal is an honest provider-reported failure, never a clean success`() = runTest {
        val f = turnEndingWith("refusal") as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.providerReported, "the BACKEND sent stop_reason=refusal — G20 provenance is upstream")
        assertTrue(f.message.contains("refused"), f.message)
        assertTrue(f.message.contains("stop_reason=refusal"), f.message)
    }

    @Test
    fun `stop_reason pause_turn is a retryable overloaded failure, not a finished answer`() = runTest {
        val f = turnEndingWith("pause_turn") as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.providerReported)
        assertTrue(f.message.contains("paused"), f.message)
    }

    // The item's own wording named only refusal + pause_turn. This third value is a HARD truncation
    // the protocol added separately from max_tokens; folding it into `incomplete` would report the
    // ordinary "ran out of room" stop for a turn the backend could not run at all.
    @Test
    fun `stop_reason model_context_window_exceeded triggers client compaction`() = runTest {
        val f = turnEndingWith("model_context_window_exceeded") as TurnOutcome.Failure
        assertEquals(ErrorType.INVALID_REQUEST, f.type)
        assertTrue(f.providerReported)
        assertTrue(f.message.contains("prompt is too long"), f.message)
        assertTrue(f.message.contains("context window"), f.message)
    }

    // must_not: a false Failure on working traffic is worse than the silent success it replaces.
    // The four clean values, an absent stop_reason, and an UNRECOGNIZED vendor value all stay
    // Success — the remainder is deliberately open-safe (see stopReasonFailure's rationale).
    @Test
    fun `clean, absent and unrecognized stop_reasons are untouched`() = runTest {
        listOf("end_turn", "stop_sequence", "tool_use", "max_tokens", "stop", "eos", "").forEach { reason ->
            val outcome = turnEndingWith(reason)
            val s = outcome as? TurnOutcome.Success
                ?: throw AssertionError("stop_reason='$reason' must stay a Success, got $outcome")
            assertEquals("partial", s.bodyText, "stop_reason='$reason' altered the turn's text")
        }
    }

    @Test
    fun `a genuine SSE error event is never overwritten by a later refusal stop_reason`() = runTest {
        val outcome = drive(
            Rec(),
            ev("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.RATE_LIMIT, f.type)
        assertTrue(f.message.contains("slow down"), f.message)
    }

    // The reverse of the first-latch test above. onError assigns failureType UNCONDITIONALLY, so a
    // genuine SSE error wins in BOTH orderings and the client always gets the more actionable
    // retryability class. Measured, not assumed — pinned so the property cannot silently reverse.
    @Test
    fun `a genuine SSE error after a latched refusal still owns the verdict`() = runTest {
        val outcome = drive(
            Rec(),
            ev("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}"""),
            ev("""{"type":"error","error":{"type":"overloaded_error","message":"try later"}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.message.contains("try later"), f.message)
        assertTrue(f.providerReported)
    }

    // NEUTRAL: an upstream that SIGNS and VERIFIES must never receive a synthesized signature back.
    // A thinking block truncated before its signature would otherwise persist "splice-synth-v1" into
    // the client transcript and hand that forgery upstream on the next turn.
    @Test
    fun `neutral never synthesizes a thinking signature`() = runTest {
        val sink = Rec()
        PassthroughStreamTranslator(ctx(), PassthroughQuirks(providerTag = "claude-splice")).driveTurn(
            listOf(
                ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
                ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"t"}}"""),
                ev("""{"type":"content_block_stop","index":0}"""),
                ev("""{"type":"message_stop"}"""),
            ).asFlow(),
            sink,
        )
        assertFalse(sink.calls.any { it.startsWith("sig:") }, sink.calls.toString())
    }

    @Test
    fun `failure text carries the head's own provider tag`() = runTest {
        val outcome = PassthroughStreamTranslator(ctx(), PassthroughQuirks(providerTag = "claude-splice")).driveTurn(
            listOf(ev("""{"type":"error","error":{"type":"api_error","message":"boom"}}""")).asFlow(),
            Rec(),
        )
        val f = outcome as TurnOutcome.Failure
        assertTrue(f.message.startsWith("claude-splice: "), f.message)
    }
}

// NF-06's second half: skipping events past the breach is not enough — a guard that merely skips
// keeps CONSUMING a runaway upstream, holding the turn slot and quota until the upstream chooses
// to close. The breach must CANCEL collection so Flow unwinds the producer. A separate class: the
// main class above is at detekt's LargeClass budget.
class PassthroughRunawayCancellationTest {

    @Test
    fun `open block registry count trips the shared capacity guard`() = runTest {
        var emitted = 0
        val events = kotlinx.coroutines.flow.flow {
            repeat(BufferCapacity.MAX_TOOL_INDEX_ENTRIES + 10) { index ->
                emitted += 1
                emit(
                    ev(
                        """{"type":"content_block_start","index":$index,
                           "content_block":{"type":"server_tool_use"}}""",
                    ),
                )
            }
        }

        val outcome = PassthroughStreamTranslator(ctx(), KIMI).driveTurn(events, Rec())
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        assertEquals(BufferCapacity.MAX_TOOL_INDEX_ENTRIES + 1, emitted, "guard must stop collection")
    }

    @Test
    fun `aggregate arguments across open tool blocks trip the shared capacity guard`() = runTest {
        val chunk = "x".repeat(1_000_000)
        val sink = Rec()
        val events = kotlinx.coroutines.flow.flow {
            repeat(25) { index ->
                emit(
                    ev(
                        """{"type":"content_block_start","index":$index,
                           "content_block":{"type":"tool_use","id":"tool_$index","name":"run"}}""",
                    ),
                )
                emit(
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("content_block_delta"))
                        put("index", kotlinx.serialization.json.JsonPrimitive(index))
                        put(
                            "delta",
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("input_json_delta"))
                                put("partial_json", kotlinx.serialization.json.JsonPrimitive(chunk))
                            },
                        )
                    },
                )
            }
        }

        val outcome = PassthroughStreamTranslator(ctx(), KIMI).driveTurn(events, sink)
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.message.contains("exceeded max buffered size"), failure.message)
        assertEquals(20, sink.calls.count { it.startsWith("json:") })
    }

    @Test
    fun `the capacity breach cancels the upstream instead of draining it`() = runTest {
        val chunk = "x".repeat(1_000_000)
        var emitted = 0
        val events = kotlinx.coroutines.flow.flow {
            emit(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
            repeat(25) {
                emitted += 1
                emit(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$chunk"}}"""))
            }
        }
        val outcome = PassthroughStreamTranslator(ctx(), KIMI).driveTurn(events, Rec())
        assertTrue(outcome is TurnOutcome.Failure, "got $outcome")
        assertTrue(emitted < 25, "the guard must unwind the upstream at the breach, not drain it; emitted=$emitted")
    }
}

// DR-118 (sweep-2): the registry's onBlockStart had no "redacted_thinking" arm, so the block fell
// to the IGNORED else and the proxy DELETED it. Claude Code replays assistant turns from what it
// received, and Anthropic requires redacted_thinking blocks back verbatim — a client that never
// got one cannot replay it, so the next signed-thinking request 400s upstream. Data rides
// content_block_start (the WireSink contract); a NEW class because the main class sits at
// detekt's LargeClass budget.
class PassthroughRedactedThinkingTest {
    @Test
    fun `a redacted_thinking block reaches the client sink, not the ignore bucket - DR-118`() = runTest {
        val sink = Rec()
        val outcome = drive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev(
                """{"type":"content_block_start","index":0,""" +
                    """"content_block":{"type":"redacted_thinking","data":"EncBlob=="}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"content_block_start","index":1,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hi"}}"""),
            ev("""{"type":"content_block_stop","index":1}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertTrue(
            sink.calls.contains("redacted:EncBlob=="),
            "the encrypted replay block must survive the proxy; calls=${sink.calls}",
        )
        // Control: the neighbouring text block still flows normally around the pass-through.
        assertTrue(sink.calls.contains("text:hi"))
    }
}

// DR-119: the branch repurposed this dialect as the faithful NEUTRAL head (CH-2: a head that
// declares nothing gets its bytes forwarded as sent), but the registry's record+swallow arm
// predates that — server_tool_use / web_search_tool_result blocks and citations_delta deltas
// were dropped, so Claude Code rendered answers citing results the client never received and
// the stored transcript lost every citation. Neutral forwards VERBATIM; kimi keeps its
// historical swallow (byte-identity law — flipping kimi bytes is operator territory).
class PassthroughServerToolForwardTest {

    private suspend fun neutralDrive(sink: Rec, vararg evs: JsonObject): TurnOutcome =
        PassthroughStreamTranslator(ctx(), PassthroughQuirks(providerTag = "claude-splice"))
            .driveTurn(evs.toList().asFlow(), sink)

    @Test
    fun `neutral head forwards a server_tool_use block and its args verbatim - DR-119`() = runTest {
        val sink = Rec()
        val outcome = neutralDrive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev(
                """{"type":"content_block_start","index":0,""" +
                    """"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            ),
            ev(
                """{"type":"content_block_delta","index":0,""" +
                    """"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"splice\"}"}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertEquals(
            listOf(
                """rawOpen:{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}""",
                """json:{"query":"splice"}""",
                "close",
                "closeAll",
            ),
            sink.calls,
        )
    }

    @Test
    fun `neutral head forwards a web_search_tool_result block verbatim - DR-119`() = runTest {
        val sink = Rec()
        val outcome = neutralDrive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev(
                """{"type":"content_block_start","index":0,"content_block":{"type":"web_search_tool_result",""" +
                    """"tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://x.test"}]}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertTrue(
            sink.calls.any { it.startsWith("""rawOpen:{"type":"web_search_tool_result"""") },
            "search results must reach the client sink; calls=${sink.calls}",
        )
    }

    @Test
    fun `neutral head forwards citations_delta on a live text block - DR-119`() = runTest {
        val sink = Rec()
        val outcome = neutralDrive(
            sink,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cited"}}"""),
            ev(
                """{"type":"content_block_delta","index":0,"delta":{"type":"citations_delta",""" +
                    """"citation":{"type":"web_search_result_location","url":"https://x.test"}}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertTrue(
            sink.calls.any { it.startsWith("rawDelta:{\"type\":\"citations_delta\"") },
            "citations must survive to the transcript; calls=${sink.calls}",
        )
        assertTrue(sink.calls.contains("text:cited"))
    }

    @Test
    fun `kimi profile still swallows citations_delta - DR-119 control`() = runTest {
        val sink = Rec()
        val outcome = drive(
            sink,
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"t"}}"""),
            ev(
                """{"type":"content_block_delta","index":0,"delta":{"type":"citations_delta",""" +
                    """"citation":{"type":"web_search_result_location","url":"https://x.test"}}}""",
            ),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_stop"}"""),
        )
        assertTrue(outcome is TurnOutcome.Success, "got $outcome")
        assertTrue(
            sink.calls.none { it.startsWith("rawDelta:") },
            "kimi behavior is byte-pinned — no citation forward; calls=${sink.calls}",
        )
    }
}
