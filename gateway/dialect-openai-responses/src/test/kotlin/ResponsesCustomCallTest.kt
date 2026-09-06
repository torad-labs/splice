import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesCustomCallParse
import splice.dialect.responses.ResponsesOutcomePayload
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext

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

    private fun item(id: String): JsonObject = buildJsonObject {
        put("type", "custom_tool_call")
        put("call_id", id)
        put("name", "splice_exec")
        put("input", "return 1;")
    }
}
