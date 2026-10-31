// NEW: failure-handling slice 2. A chat backend's failure quotes the backend's own words only through
// ProviderWordsFilter: an error event's message field and a model's refusal are the words its cause carries for the
// line a person reads, cut to what a person may read, while the log keeps them whole.
package splice.dialect.chat

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.turn.TurnOutcome

class ChatProviderWordsTest {

    private suspend fun failure(vararg frames: String): TurnOutcome.Failure =
        ChatStreamTranslator(ctx()).driveTurn(frames.map(::ev).asFlow(), Rec()) as TurnOutcome.Failure

    private fun saidOf(f: TurnOutcome.Failure) = (f.cause as FailureCause.Provider).said

    // Whatever the classifier makes of an error event, typed or not, its cause carries the message field through the
    // filter, so no id, field path, host or retry advice reaches a line.
    @Test
    fun `an error event's cause quotes its message through the filter, and the log keeps the message`() = runTest {
        val messages = listOf(
            "The model hit an internal fault while sampling.",
            "Something went wrong in the model (request id: 7f3a2c19-1b2d-4e5f-8a9b-0c1d2e3f4a5b).",
            "Context exceeded: this model's maximum context length is 131072 tokens.",
            "Rate limit reached for gpt-5.6 in organization org-abc on tokens per min. Please try again in 2s.",
            "Invalid schema for function 'Read': input_schema.properties.path is not valid.",
        )
        for (message in messages) {
            for (typed in listOf(false, true)) {
                val event = buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("message", message)
                            if (typed) put("type", "server_error")
                        },
                    )
                }

                val f = failure(event.toString())

                assertEquals(ProviderWordsFilter.of(message), saidOf(f), "typed=$typed: $message")
                assertTrue(message in f.evidence.message, f.evidence.message)
            }
        }
    }

    @Test
    fun `a model's refusal is the provider's words its cause quotes`() = runTest {
        val f = failure(
            """{"choices":[{"delta":{"refusal":"I can't help with "}}]}""",
            """{"choices":[{"delta":{"refusal":"that request."}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        assertEquals(
            FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of("I can't help with that request.")),
            f.cause,
        )
        assertTrue("I can't help with that request." in f.evidence.message, f.evidence.message)
    }
}
