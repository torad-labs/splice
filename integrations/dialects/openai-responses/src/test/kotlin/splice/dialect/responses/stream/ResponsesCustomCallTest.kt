package splice.dialect.responses.stream

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
import splice.core.turn.SpliceNotice
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

/** [suspending] makes a signature write suspend first, as a contended client write does. */
private class ProgressSink(
    private val recorded: RecordingSink = RecordingSink(),
    private val suspending: Boolean = false,
) : WireSink by recorded {
    val calls: List<String> get() = recorded.calls
    val signatures = mutableListOf<String>()
    val source = mutableListOf<CustomToolSource>()
    val deltas: List<String> get() = source.filterIsInstance<CustomToolSource.Delta>().map { it.text }

    /** What the runtime observed and what the client was shown, in one order. */
    val timeline = mutableListOf<String>()

    override suspend fun customToolSource(event: CustomToolSource) {
        source += event
        if (event is CustomToolSource.Delta) timeline += "runtime:${event.text}"
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        timeline += "shown:$thinking"
        recorded.thinkingDelta(index, thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        if (suspending) yield()
        signatures += signature
        recorded.calls += "signature#${index.value}:$signature"
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
        val outcome = payload.successOutcome(state) as TurnOutcome.Success
        assertEquals(listOf("first", "second"), outcome.handoffs.customCalls.map { it.callId })
        assertEquals(listOf(first, second), outcome.handoffs.customCalls.map { it.raw })
    }

    @Test
    fun `streamed-only custom call survives empty terminal output`() {
        val first = item("first")
        val state = ResponsesTurnState()
        state.customCalls += checkNotNull(parser.parse(first))
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(emptyList())))
        val outcome = payload.successOutcome(state) as TurnOutcome.Success
        assertEquals(listOf(first), outcome.handoffs.customCalls.map { it.raw })
    }

    @Test
    fun `duplicate terminal call ids remain visible to bridge validation`() {
        val first = item("duplicate")
        val state = ResponsesTurnState()
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(listOf(first, first))))
        val outcome = payload.successOutcome(state) as TurnOutcome.Success
        assertEquals(2, outcome.handoffs.customCalls.size)
    }

    /** V4-456: the script streams to the client while the model writes it, and the runtime still
     *  observes every delta. Oct 1's version fed only the runtime, and the client saw the step at once. */
    @Test
    fun `exec input is visible before completion and its signed progress does not change replay`() = runTest {
        val sink = ProgressSink()
        val script = "text(await tools.Read({file_path: 'a'}));"
        val call = execItem("live")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = script))
            assertTrue(sink.calls.any { it == "think#0:$script" }, "the input must be visible before item-done")
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
        assertEquals(listOf("runtime:$script", "shown:$script"), sink.timeline, "showing must never delay a dispatch")
        assertEquals(listOf(SpliceNotice.SIGNATURE), sink.signatures)
        assertEquals(1, sink.calls.count { it.startsWith("openThinking") })
        assertEquals(1, sink.calls.count { it == "think#0:$script" })
        assertEquals(1, sink.calls.count { it == "close#0" })
        sink.openTool("client-call", "Read")
        assertTrue(sink.calls.indexOf("close#0") < sink.calls.indexOf("openTool#1(client-call,Read)"))
        val replayed = buildJsonObject {
            put("type", "thinking")
            put("thinking", script)
            put("signature", sink.signatures.single())
        }
        assertEquals(replayRequest(null).toString(), replayRequest(replayed).toString())
    }

    @Test
    fun `an exec progress block is signed even when item-done is missing`() = runTest {
        val sink = ProgressSink()
        val call = execItem("missing")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "return 1;"))
            emit(terminal(call))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertEquals(listOf("return 1;"), sink.deltas)
        assertEquals(listOf("think#0:return 1;"), sink.calls.filter { it.startsWith("think#") })
        assertEquals(listOf(SpliceNotice.SIGNATURE), sink.signatures)
        assertEquals(1, sink.calls.count { it == "close#0" })
    }

    @Test
    fun `another custom tool's input is not rendered as exec progress`() = runTest {
        val sink = ProgressSink()
        val call = item("other")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "private input"))
            emit(customEvent("response.output_item.done", call))
            emit(terminal(call))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertFalse(sink.calls.any { it.startsWith("think") || it.startsWith("openThinking") })
        assertEquals(emptyList<String>(), sink.signatures)
    }

    @Test
    fun `cancelling mid-input signs and closes the exec block before propagation`() = runTest {
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
        assertTrue(sink.calls.contains("think#0:await tools.Read("))
        turn.cancelAndJoin()
        assertTrue(turn.isCancelled)
        assertEquals(listOf(SpliceNotice.SIGNATURE), sink.signatures)
        assertEquals(1, sink.calls.count { it == "close#0" })
    }

    /** The cleanup runs NonCancellable: a client write that suspends in a cancelled turn still signs and
     *  closes the script, where a cancellable one would throw before the signature. */
    @Test
    fun `cancelling mid-input signs the exec block even when the client write suspends`() = runTest {
        val sink = ProgressSink(suspending = true)
        val upstream = flow {
            emit(customEvent("response.output_item.added", execItem("contended")))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "await tools.Read("))
            awaitCancellation()
        }
        val turn = async(start = CoroutineStart.UNDISPATCHED) {
            ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        }
        turn.cancelAndJoin()
        assertEquals(listOf(SpliceNotice.SIGNATURE), sink.signatures)
        assertEquals(1, sink.calls.count { it == "close#0" })
    }

    @Test
    fun `index reuse closes progress before tools and stale input does not enter the replacement`() = runTest {
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
        assertEquals(listOf(SpliceNotice.SIGNATURE), sink.signatures)
        assertTrue(sink.calls.indexOf("close#0") < sink.calls.indexOf("openTool#1(client-tool,Read)"))
        assertEquals(listOf("think#0:return ", "think#0: "), sink.calls.filter { it.startsWith("think#") })
    }

    @Test
    fun `stale input on a reused exec index is dropped and a malformed done still closes its block`() = runTest {
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
        assertEquals(listOf("think#0:first", "think#1:second"), sink.calls.filter { it.startsWith("think#") })
        assertEquals(listOf(SpliceNotice.SIGNATURE, SpliceNotice.SIGNATURE), sink.signatures)
        assertEquals(1, sink.calls.count { it == "close#0" })
        assertEquals(1, sink.calls.count { it == "close#1" })
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
