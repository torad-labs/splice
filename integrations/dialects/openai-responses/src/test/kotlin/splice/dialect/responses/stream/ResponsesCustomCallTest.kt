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
import splice.core.turn.ReasoningDisplayParser
import splice.core.turn.SharedSummaryParts
import splice.core.turn.SpliceNotice
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

private class ProgressSink(private val recorded: RecordingSink = RecordingSink()) : WireSink by recorded {
    val calls: List<String> get() = recorded.calls
    val signatures = mutableListOf<String>()
    val source = mutableListOf<CustomToolSource>()
    val deltas: List<String> get() = source.filterIsInstance<CustomToolSource.Delta>().map { it.text }

    override suspend fun customToolSource(event: CustomToolSource) {
        source += event
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
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
            clientGone = { false },
            watchdogFired = { null },
            streamIdleMsForMessage = 1_000,
            upstreamTimeoutMsForMessage = 5_000,
            summaryPartsShared = SharedSummaryParts(),
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
        assertEquals(listOf("first", "second"), outcome.customCalls.map { it.callId })
        assertEquals(listOf(first, second), outcome.customCalls.map { it.raw })
    }

    @Test
    fun `streamed-only custom call survives empty terminal output`() {
        val first = item("first")
        val state = ResponsesTurnState()
        state.customCalls += checkNotNull(parser.parse(first))
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(emptyList())))
        val outcome = payload.successOutcome(state) as TurnOutcome.Success
        assertEquals(listOf(first), outcome.customCalls.map { it.raw })
    }

    @Test
    fun `duplicate terminal call ids remain visible to bridge validation`() {
        val first = item("duplicate")
        val state = ResponsesTurnState()
        state.finalResponse = JsonObject(mapOf("output" to JsonArray(listOf(first, first))))
        val outcome = payload.successOutcome(state) as TurnOutcome.Success
        assertEquals(2, outcome.customCalls.size)
    }

    @Test
    fun `exec input is observed before completion but never renders raw source as thinking`() = runTest {
        val sink = ProgressSink()
        val script = "text(await tools.Read({file_path: 'a'}));"
        val call = execItem("live")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = script))
            assertFalse(sink.calls.any { it.startsWith("think#") }, "raw exec source must not reach client text")
            emit(customEvent("response.custom_tool_call_input.done", delta = script))
            emit(customEvent("response.output_item.done", call))
            emit(customEvent("response.output_item.done", call))
            emit(terminal(call))
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink) as TurnOutcome.Success
        assertEquals("", outcome.thinkingText)
        assertEquals(emptyList<String>(), outcome.reasoningEnvelopes)
        assertEquals(call, outcome.customCalls.single().raw)
        assertTrue(sink.signatures.isEmpty())
        assertEquals(listOf(script), sink.deltas)
        assertFalse(sink.calls.any { it.startsWith("openThinking") || it.startsWith("think#") })
        val replayed = buildJsonObject {
            put("type", "thinking")
            put("thinking", "Waiting for the next statement")
            put("signature", SpliceNotice.SIGNATURE)
        }
        assertEquals(replayRequest(null).toString(), replayRequest(replayed).toString())
    }

    @Test
    fun `missing item-done still observes source without creating a thinking block`() = runTest {
        val sink = ProgressSink()
        val call = execItem("missing")
        val upstream = flow {
            emit(customEvent("response.output_item.added", call))
            emit(customEvent("response.custom_tool_call_input.delta", delta = "return 1;"))
            emit(terminal(call))
        }
        ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink)
        assertTrue(sink.signatures.isEmpty())
        assertFalse(sink.calls.any { it.startsWith("think#") })
        assertEquals(listOf("return 1;"), sink.deltas)
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
    fun `cancelling mid-input propagates without publishing source text`() = runTest {
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
        assertTrue(sink.signatures.isEmpty())
        assertFalse(sink.calls.any { it.startsWith("think#") })
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
        assertTrue(sink.signatures.isEmpty())
        assertTrue(sink.calls.any { it == "openTool#0(client-tool,Read)" })
        assertEquals(listOf("return ", " "), sink.deltas)
        assertFalse(sink.calls.any { it.startsWith("think#") })
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
        assertTrue(sink.signatures.isEmpty())
        assertFalse(sink.calls.any { it.startsWith("think#") })
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
            originalModel = "claudex--m",
            upstreamModel = "m",
            configEffort = null,
            configSummary = null,
            showReasoning = ReasoningDisplayParser.from("text"),
            replayReasoning = InjectPriorReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        return ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex"))
            .build(parsed.typed, parsed.raw, options).req
    }

    private fun item(id: String): JsonObject = buildJsonObject {
        put("type", "custom_tool_call")
        put("call_id", id)
        put("name", "splice_exec")
        put("input", "return 1;")
    }
}
