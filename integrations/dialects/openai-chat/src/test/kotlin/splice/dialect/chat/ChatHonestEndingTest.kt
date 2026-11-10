// What a chat stream's ending tells the client: a finish_reason the dialect does not know fails the turn instead of
// grading the cut-off text a clean stop; a finished empty answer is the model's closed message; a stream torn
// mid-arguments is a tear, not the backend's corrupt JSON; a finished stream with its bill is read no further; a new
// call reusing an open index is its own tool_use.
package splice.dialect.chat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome

class ChatHonestEndingTest {

    @Test
    fun `a finish_reason that names no finished answer fails the turn`() = runTest {
        val outcome = driveEvents(
            ev("""{"choices":[{"delta":{"content":"half an ans"}}]}"""),
            ev("""{"choices":[{"delta":{},"finish_reason":"insufficient_system_resource"}]}"""),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.cause is FailureCause.Provider.Unrecognized, "${failure.cause}")
        assertTrue(failure.evidence.message.contains("insufficient_system_resource"), failure.evidence.message)
    }

    @Test
    fun `a finished empty answer is the model's closed message, a cut one is not`() = runTest {
        val stopped = driveEvents(ev("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")) as TurnOutcome.Success
        assertTrue(stopped.shape.messageClosed)
        val cut = driveEvents(ev("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")) as TurnOutcome.Success
        assertFalse(cut.shape.messageClosed)
    }

    @Test
    fun `a stream torn mid tool arguments is a tear with the tool open`() = runTest {
        val outcome = driveEvents(
            ev(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"Write",""" +
                    """"arguments":"{\"path\":\"a"}}]}}]}""",
            ),
        )
        val cause = (outcome as TurnOutcome.Failure).cause
        assertEquals(FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = true), cause)
    }

    @Test
    fun `a finished stream with its bill ends without waiting for the socket to close`() = runTest {
        val upstream = flow {
            emit(ev("""{"choices":[{"delta":{"content":"done"}}]}"""))
            emit(ev("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
            emit(ev("""{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""))
            awaitCancellation()
        }
        val outcome = ChatStreamTranslator(ctx()).driveTurn(upstream, Rec()) as TurnOutcome.Success
        assertEquals(1, outcome.usage.outputTokens)
    }

    @Test
    fun `a new call reusing an open index is its own tool_use`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"Read",""" +
                        """"arguments":"{\"f\":1}"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_b","function":{"name":"Grep",""" +
                        """"arguments":"{\"q\":2}"}}]}}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success, "$outcome")
        assertEquals(listOf("call_a" to "Read", "call_b" to "Grep"), sink.toolOpens)
    }
}
