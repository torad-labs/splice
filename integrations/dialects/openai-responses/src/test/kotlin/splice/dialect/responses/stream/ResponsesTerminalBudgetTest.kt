package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.responses.reasoning.ReasoningEnvelopeEncoder
import splice.upstream.transport.BufferCapacity

/** The dialect must return a real local failure; its WireSink has no message_stop or error-terminal authority. */
class ResponsesTerminalBudgetTest {
    private val streamedChars = BufferCapacity.MAX_BUFFERED_CHARS * 2 / 5

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `terminal-only envelope cannot exceed the budget or flush a held literal`(heldLiteral: Boolean) = runTest {
        val sink = RecordingSink()
        val envelope = "e".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 4)
        val events = streamed() + if (heldLiteral) listOf(delta("reasoning_summary", 2, "<")) else emptyList()
        val outcome = drive(events + terminal(reasoning(envelope = envelope)), sink)

        assertLocalCapacityFailure(outcome)
        assertEquals(1, sink.calls.count { it.startsWith("think#") }, "only the previously delivered reasoning remains")
        assertFalse(sink.calls.any { it.startsWith("think#") && it.substringAfter(':') == "<" })
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") }, "overflow cannot flush held presentation")
        assertFalse(sink.calls.any { it.startsWith("redacted:") }, "opaque data is not a substitute for failure")
    }

    @Test
    fun `below-cap terminal envelope retains exact bytes and settles a held literal once`() = runTest {
        val sink = RecordingSink()
        val envelope = "e".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 20)
        val events = streamed() + delta("reasoning_summary", 2, "<") + terminal(reasoning(envelope = envelope))
        val outcome = drive(events, sink)

        assertTrue(outcome is TurnOutcome.Success, "terminal harvesting below the cap must stay successful")
        val success = outcome as TurnOutcome.Success
        assertEquals(listOf(envelope), success.handoffs.reasoningEnvelopes)
        assertEquals(streamedChars, success.text.bodyText.length)
        val visible = sink.calls.filter { it.startsWith("think#") || it.startsWith("text#") }
        assertEquals(3, visible.size)
        assertEquals("<", visible.last().substringAfter(':'))
        assertEquals(2, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
        assertEquals(7, success.usage.inputTokens)
        assertEquals(11, success.usage.outputTokens)
    }

    @Test
    fun `terminal-readable backfill is checked before returning a clean success`() = runTest {
        val sink = RecordingSink()
        val readable = "r".repeat(BufferCapacity.MAX_BUFFERED_CHARS - streamedChars + 1)
        val outcome = drive(
            listOf(delta("output", 0, "a".repeat(streamedChars)), terminal(reasoning(readable = readable))),
            sink,
        )

        assertLocalCapacityFailure(outcome)
        assertFalse(sink.calls.any { it.startsWith("openThinking#") }, "terminal backfill is not live delivery")
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    @Test
    fun `below-cap terminal-readable backfill keeps its exact model text`() = runTest {
        val sink = RecordingSink()
        val readable = "r".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 2)
        val outcome = drive(
            listOf(delta("output", 0, "a".repeat(streamedChars)), terminal(reasoning(readable = readable))),
            sink,
        )

        assertTrue(outcome is TurnOutcome.Success)
        val success = outcome as TurnOutcome.Success
        assertEquals(readable, success.text.thinkingText)
        assertFalse(success.text.emittedThinking, "harvested thinking is not evidence of client delivery")
        assertFalse(sink.calls.any { it.startsWith("openThinking#") || it.startsWith("redacted:") })
    }

    private fun assertLocalCapacityFailure(outcome: TurnOutcome) {
        assertTrue(outcome is TurnOutcome.Failure, "terminal additions must not bypass the shared char limit")
        val failure = outcome as TurnOutcome.Failure
        assertEquals("upstream: response exceeded max buffered size; aborting", failure.message)
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertEquals(FailureCause.TOOL_TEAR, failure.cause)
        assertEquals(FailurePhase.MID_OUTPUT, failure.phase)
        assertFalse(failure.traits.providerReported, "shared-capacity rejection is local")
        assertEquals(7, failure.salvagedUsage.inputTokens)
        assertEquals(11, failure.salvagedUsage.outputTokens)
    }

    private suspend fun drive(events: List<JsonObject>, sink: RecordingSink): TurnOutcome {
        // The synthetic encoder preserves known opaque chars exactly, without compression-size assumptions.
        val context = ctx(emit = true, collect = true).copy(
            encodeReasoningEnvelope = ReasoningEnvelopeEncoder { JsonScalars.str(it, "encrypted_content") },
        )
        return ResponsesStreamTranslator(context).driveTurn(events.asFlow(), sink)
    }

    private fun streamed(): List<JsonObject> = listOf(
        delta("reasoning", 0, "r".repeat(streamedChars)),
        delta("output", 1, "a".repeat(streamedChars)),
    )

    private fun delta(channel: String, index: Int, text: String): JsonObject = buildJsonObject {
        put("type", "response.${channel}_text.delta")
        put("output_index", index)
        put("delta", text)
    }

    private fun reasoning(envelope: String? = null, readable: String? = null): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "terminal-only")
        if (envelope != null) put("encrypted_content", envelope)
        if (readable != null) put("text", readable)
    }

    private fun terminal(item: JsonObject): JsonObject = buildJsonObject {
        put("type", "response.completed")
        put(
            "response",
            buildJsonObject {
                put("status", "completed")
                put("output", JsonArray(listOf(item)))
                put(
                    "usage",
                    buildJsonObject {
                        put("input_tokens", 7)
                        put("output_tokens", 11)
                    },
                )
            },
        )
    }
}
