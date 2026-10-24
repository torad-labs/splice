package splice.dialect.responses.stream

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.SummaryHandling
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.upstream.TurnSignals
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

/** What the runtime observed of the script, and every call the client sink received. */
private class ProgressSink(private val recorded: RecordingSink = RecordingSink()) : WireSink by recorded {
    val calls: List<String> get() = recorded.calls
    val source = mutableListOf<CustomToolSource>()
    val deltas: List<String> get() = source.filterIsInstance<CustomToolSource.Delta>().map { it.text }

    override suspend fun customToolSource(event: CustomToolSource) {
        source += event
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        recorded.calls.add("signature#${index.value}:$signature")
    }

    /** Nothing a client renders: no block was opened, written to or signed for the script. The turn-end closeAll sweep
     *  is not counted: with no block open it writes nothing. */
    fun assertNothingShown() {
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("think") || it.startsWith("openThinking") })
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("signature") })
    }
}

class ResponsesCustomCallTest {
    private val parser = ResponsesCustomCallParse()
    private val payload = ResponsesOutcomePayload(
        StreamTurnContext(
            compact = false,
            emitEncryptedReasoning = EmitEncryptedReasoning(false),
            encodeReasoningEnvelope = { "unused" },
            signals = TurnSignals(
                clientGone = { false },
                watchdogFired = { null },
            ),
            caps = WatchdogCaps(
                streamIdleMs = 1_000,
                upstreamTimeoutMs = 5_000,
            ),
            summary = SummaryHandling(
                partsShared = SharedSummaryParts(),
            ),
        ),
    )

    @Test
    fun `terminal-only sibling survives partial streamed capture without duplicating overlap`() {
        val first = item("first")
        val second = item("second")
        val state = ResponsesTurnState()
        state.customCalls += checkNotNull(parser.parse(first))
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(listOf(first, second))))
        val outcome = payload.successOutcome(state)
        assertEquals(listOf("first", "second"), outcome.handoffs.customCalls.map { it.callId })
        assertEquals(listOf(first, second), outcome.handoffs.customCalls.map { it.raw })
    }

    @Test
    fun `streamed-only custom call survives empty terminal output`() {
        val first = item("first")
        val state = ResponsesTurnState()
        state.customCalls += checkNotNull(parser.parse(first))
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(emptyList())))
        val outcome = payload.successOutcome(state)
        assertEquals(listOf(first), outcome.handoffs.customCalls.map { it.raw })
    }

    @Test
    fun `duplicate terminal call ids remain visible to bridge validation`() {
        val first = item("duplicate")
        val state = ResponsesTurnState()
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(listOf(first, first))))
        val outcome = payload.successOutcome(state)
        assertEquals(2, outcome.handoffs.customCalls.size)
    }

    /** Oct 12 (Marcos's decision, reversing afb7a1f87's display): the runtime observes every delta of the script as the
     *  model writes it, and no byte of it reaches the client. */
    @Test
    fun `exec input reaches the runtime as it streams and none of it reaches the client`() = runTest {
        val sink = ProgressSink()
        val script = "text(await tools.Read({file_path: 'a'}));"
        val call = execItem("live")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = script))
            assertEquals(listOf(script), sink.deltas, "the runtime has the input before item-done")
            emit(customEvent("response.custom_tool_call_input.done", delta = script))
            emit(customEvent("response.output_item.done", call))
            emit(customEvent("response.output_item.done", call))
            emit(terminal(call))
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink) as TurnOutcome.Success
        assertEquals("", outcome.text.thinkingText)
        assertEquals(emptyList<String>(), outcome.handoffs.reasoningEnvelopes)
        assertEquals(call, outcome.handoffs.customCalls.single().raw)
        assertEquals(listOf(script), sink.deltas)
        sink.assertNothingShown()
        assertFalse(sink.calls.any { script in it }, "no script byte on the client: ${sink.calls}")
        sink.openTool("client-call", "Read")
        assertTrue(sink.calls.contains("openTool#0(client-call,Read)"), "the call it dispatches still shows as a tool")
        assertEquals(replayRequest(null).toString(), replayRequest(null).toString())
    }

    @Test
    fun `an exec item with no item-done still shows the client nothing`() = runTest {
        val sink = ProgressSink()
        val call = execItem("missing")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "return 1;"))
            emit(terminal(call))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertEquals(listOf("return 1;"), sink.deltas)
        sink.assertNothingShown()
    }

    @Test
    fun `another custom tool's input is not fed to the runtime or the client`() = runTest {
        val sink = ProgressSink()
        val call = item("other")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "private input"))
            emit(customEvent("response.output_item.done", call))
            emit(terminal(call))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertEquals(emptyList<String>(), sink.deltas)
        sink.assertNothingShown()
    }

    @Test
    fun `cancelling mid-input leaves nothing open on the client`() = runTest {
        val sink = ProgressSink()
        val upstream = flow {
            emit(customEvent("response.output_item.added", execItem("cancelled")))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "await tools.Read("))
            awaitCancellation()
        }
        val turn = async(start = CoroutineStart.UNDISPATCHED) {
            ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        }
        assertEquals(listOf("await tools.Read("), sink.deltas)
        turn.cancelAndJoin()
        assertTrue(turn.isCancelled)
        sink.assertNothingShown()
    }

    @Test
    fun `index reuse keeps stale input out of the replacement and shows the client no script`() = runTest {
        val sink = ProgressSink()
        val upstream = flow {
            emit(customEvent("response.output_item.added", execItem("original")))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "return "))
            emit(customEvent("response.custom_tool_call_input.delta", delta = " "))
            val tool = buildJsonObject {
                put("type", "function_call")
                put("call_id", "client-tool")
                put("name", "Read")
                put("arguments", "{}")
            }
            emit(customEvent("response.output_item.added", tool))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "stale"))
            emit(customEvent("response.output_item.done", tool))
            emit(terminal(execItem("original")))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertEquals(listOf("return ", " "), sink.deltas)
        sink.assertNothingShown()
        val shown = sink.calls.any { it.startsWith("openTool#0(client-tool,Read)") }
        assertTrue(shown, "the tool still shows: ${sink.calls}")
    }

    @Test
    fun `stale input on a reused exec index is dropped and a malformed done shows nothing`() = runTest {
        val sink = ProgressSink()
        val upstream = flow {
            emit(customEvent("response.output_item.added", execItem("first")))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "first"))
            emit(customEvent("response.output_item.added", execItem("second")))
            val stale = customEvent("response.custom_tool_call_input.delta", delta = "stale")
            emit(JsonObject(stale + ("item_id" to JsonPrimitive("first"))))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "second"))
            emit(customEvent("response.output_item.done"))
            emit(terminal(execItem("second")))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertEquals(listOf("first", "second"), sink.deltas)
        sink.assertNothingShown()
    }

    private fun execItem(id: String): JsonObject = JsonObject(
        item(id) + mapOf("name" to JsonPrimitive("exec"), "id" to JsonPrimitive(id)),
    )

    private fun customEvent(type: String, item: JsonObject? = null, delta: String? = null) = buildJsonObject {
        put("type", type)
        put("output_index", 0)
        item?.let { put("item", it) }
        delta?.let { put("delta", it) }
    }

    private fun terminal(call: JsonObject) = buildJsonObject {
        put("type", "response.completed")
        put("response", buildJsonObject { put("output", JsonArray(listOf(call))) })
    }

    private fun replayRequest(progress: JsonObject?): JsonObject {
        val content = buildList {
            progress?.let(::add)
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", "answer")
                },
            )
        }
        val body = buildJsonObject {
            put("model", "m")
            val message = buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(content))
            }
            put("messages", JsonArray(listOf(message)))
        }
        val parsed = AnthropicParse.parseAnthropicBody(body.toString())
        val options = BuildOptions(
            compact = false,
            models = ModelIds(
                original = "claudex--m",
                upstream = "m",
            ),
            reasoning = RequestedReasoning(
                effort = null,
                summary = null,
                display = ReasoningDisplay.TEXT,
            ),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(false),
                decode = { null },
            ),
        )
        return ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()))
            .build(parsed.typed, parsed.raw, options).req
    }

    private fun item(id: String): JsonObject = buildJsonObject {
        put("type", "custom_tool_call")
        put("call_id", id)
        put("name", "splice_exec")
        put("input", "return 1;")
    }
}
