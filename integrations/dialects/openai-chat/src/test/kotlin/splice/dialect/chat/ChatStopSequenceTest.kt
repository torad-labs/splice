// NEW (Nov 14, 2026): the client's stop strings on the chat dialect, which sends its backend no stop parameter and used
// to let the answer run on past them.
package splice.dialect.chat

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

class ChatStopSequenceTest {

    @Test
    fun `the answer stops at the first stop string and the turn ends on it`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx().copy(stopSequences = listOf("</block>"))).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"content":"<block>no</bl"}}]}"""),
                ev("""{"choices":[{"delta":{"content":"ock> and more"}}]}"""),
                ev("""{"choices":[{"delta":{},"finish_reason":"length"}]}"""),
            ).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals(listOf("openText", "text:<block>no", "closeAll"), sink.calls)
        assertEquals("</block>", outcome.stopSequence)
        assertEquals("<block>no", outcome.text.bodyText)
        assertFalse(outcome.incomplete, "the answer ended at its stop string, not at the length cap")
    }
}
