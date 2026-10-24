package splice.dialect.responses.stream

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ReasoningCapture
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.SummaryHandling
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.upstream.TurnSignals
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

class ResponsesNativeWaitTest {
    private val wait = buildJsonObject {
        put("type", "function_call")
        put("id", "wait-item")
        put("call_id", "wait-call")
        put("name", "wait")
        put("arguments", "{\"cell_id\":\"1\",\"yield_time_ms\":50}")
    }

    @Test
    fun `armed wait reaches the gateway at item completion and never becomes a client tool`() = runTest {
        val recorded = RecordingSink()
        val sources = mutableListOf<CustomToolSource>()
        val sink = object : WireSink by recorded {
            override suspend fun customToolSource(event: CustomToolSource) {
                sources += event
            }
        }
        val upstream = flow {
            emit(event("response.output_item.added", wait))
            emit(event("response.output_item.done", wait))
            assertEquals("wait-call", sources.filterIsInstance<CustomToolSource.Completed>().single().call.callId)
            emit(terminal(emptyList()))
        }
        val result = ResponsesStreamTranslator(context(true)).driveTurn(upstream, sink) as TurnOutcome.Success
        assertEquals(wait, result.handoffs.customCalls.single().raw)
        assertEquals(listOf(wait), result.handoffs.nativeOutput)
        assertFalse(recorded.calls.any { it.startsWith("openTool") })
    }

    @Test
    fun `a normal client function named wait retains its ordinary tool delivery`() = runTest {
        val sink = RecordingSink()
        val result = ResponsesStreamTranslator(context(false)).driveTurn(
            flow {
                emit(event("response.output_item.added", wait))
                emit(event("response.output_item.done", wait))
                emit(terminal(listOf(wait)))
            },
            sink,
        ) as TurnOutcome.Success
        assertTrue(result.hasToolUse)
        assertTrue(result.handoffs.customCalls.isEmpty())
        assertTrue(sink.calls.any { it.contains("wait-call,wait") })
    }

    @Test
    fun `sparse terminal retains encrypted reasoning and exact final message`() = runTest {
        val reasoning = buildJsonObject {
            put("type", "reasoning")
            put("id", "opaque-reasoning")
            put("encrypted_content", "synthetic-encrypted-history")
            put("summary", JsonArray(emptyList()))
        }
        val message = buildJsonObject {
            put("type", "message")
            put("id", "final-message")
            put("role", "assistant")
            put("phase", "final_answer")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", "Exact ordinary final answer")
                        },
                    ),
                ),
            )
        }
        val result = ResponsesStreamTranslator(context(true)).driveTurn(
            flow {
                emit(event("response.output_item.done", reasoning, 0))
                emit(event("response.output_item.done", message, 1))
                emit(terminal(emptyList()))
            },
            RecordingSink(),
        ) as TurnOutcome.Success
        assertEquals(listOf(reasoning, message), result.handoffs.nativeOutput)
        assertEquals("Exact ordinary final answer", result.text.bodyText)
        assertEquals("", result.text.thinkingText)
    }

    private fun context(codeMode: Boolean): StreamTurnContext = StreamTurnContext(
        compact = false,
        emitEncryptedReasoning = EmitEncryptedReasoning(false),
        encodeReasoningEnvelope = { "fixture-opaque-envelope" },
        signals = TurnSignals(clientGone = { false }, watchdogFired = { null }),
        caps = WatchdogCaps(streamIdleMs = 1_000, upstreamTimeoutMs = 5_000),
        summary = SummaryHandling(partsShared = SharedSummaryParts()),
        reasoningCapture = ReasoningCapture(codeMode = codeMode),
    )

    private fun event(type: String, item: JsonObject, index: Int = 0): JsonObject = buildJsonObject {
        put("type", type)
        put("output_index", index)
        put("item", item)
    }

    private fun terminal(items: List<JsonObject>): JsonObject = buildJsonObject {
        put("type", "response.completed")
        put(
            "response",
            buildJsonObject {
                put("status", "completed")
                put("output", JsonArray(items))
            },
        )
    }
}
