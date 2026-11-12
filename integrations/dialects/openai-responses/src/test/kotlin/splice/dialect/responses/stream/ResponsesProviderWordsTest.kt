// NEW: failure-handling slice 2. A Responses backend's failure quotes the backend's own words only through
// ProviderWordsFilter: a failure event's message field (never its code, and never splice's placeholder for a message
// that is missing) and a model's refusal are the words its cause carries for the line a person reads, while the log
// keeps them whole.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.turn.TurnOutcome

private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class ResponsesProviderWordsTest {

    private suspend fun failure(vararg events: JsonObject): TurnOutcome.Failure =
        ResponsesStreamTranslator(ctx()).driveTurn(events.toList().asFlow(), RecordingSink()) as TurnOutcome.Failure

    private fun saidOf(f: TurnOutcome.Failure) = (f.cause as FailureCause.Provider).said

    @Test
    fun `a failure event's cause quotes its message field through the filter, never its code`() = runTest {
        val messages = listOf(
            "The server had an error while processing your request. Sorry about that!",
            "An error occurred while processing your request (request ID: 6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f).",
            "Your input exceeds the context window of this model.",
            "Rate limit reached for gpt-5.6 on tokens per min. Please try again in 1.2s.",
            "Invalid 'input[3].content': string too long.",
        )
        for (code in listOf("server_error", "rate_limit_exceeded", "context_length_exceeded")) {
            for (message in messages) {
                val failed = buildJsonObject {
                    put("type", "response.failed")
                    put(
                        "response",
                        buildJsonObject {
                            put(
                                "error",
                                buildJsonObject {
                                    put("code", code)
                                    put("message", message)
                                },
                            )
                        },
                    )
                }

                val f = failure(failed)

                assertEquals(ProviderWordsFilter.of(message), saidOf(f), "$code: $message")
                assertTrue(message in f.evidence.message, f.evidence.message)
            }
        }
    }

    // DR-109's gateway shape: the error is a plain string, and it is the message.
    @Test
    fun `a plain string error is the message its cause quotes`() = runTest {
        val f = failure(event("""{"type":"error","error":"The gateway could not reach the model."}"""))

        assertEquals(ProviderWordsFilter.of("The gateway could not reach the model."), saidOf(f))
    }

    @Test
    fun `a failure event with no message quotes nothing`() = runTest {
        val f = failure(event("""{"type":"response.failed","response":{"error":{"code":"server_error"}}}"""))

        assertNull(saidOf(f), "${f.cause}")
    }

    @Test
    fun `a model's refusal is the provider's words its cause quotes`() = runTest {
        val f = failure(
            event("""{"type":"response.refusal.delta","output_index":0,"delta":"I won't "}"""),
            event("""{"type":"response.refusal.delta","output_index":0,"delta":"help write that."}"""),
        )

        assertEquals(
            FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of("I won't help write that.")),
            f.cause,
        )
        assertTrue("I won't help write that." in f.evidence.message, f.evidence.message)
    }
}
