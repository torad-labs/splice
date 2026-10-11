// What the live listing asks of this dialect's re-anchor rule: would a failure of the round, as it stands now,
// still be continued? A spent budget and an opened tool call read false; prose never does, because this dialect
// carries on from prose and restarts whole without it.
package splice.dialect.responses.reasoning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.DEFAULT_MAX_CONTINUATIONS
import splice.upstream.LiveRound

class ResponsesWouldContinueTest {
    private val policy = ResponsesReanchorPolicy(ReasoningEnvelopeDecoder { null })

    private fun round(attempt: Int = 0, tool: Boolean = false, text: Boolean = false) =
        LiveRound(attempt, toolOpened = tool, textWritten = text)

    @Test
    fun `a round with nothing shown, or prose only, would be continued`() {
        assertEquals(true, policy.wouldContinue(round()))
        assertEquals(true, policy.wouldContinue(round(text = true)))
    }

    @Test
    fun `a round that has opened a tool call would not be`() {
        assertEquals(false, policy.wouldContinue(round(tool = true)))
        assertEquals(false, policy.wouldContinue(round(tool = true, text = true)))
    }

    @Test
    fun `a spent continuation budget would not be`() {
        assertEquals(true, policy.wouldContinue(round(attempt = DEFAULT_MAX_CONTINUATIONS - 1)))
        assertEquals(false, policy.wouldContinue(round(attempt = DEFAULT_MAX_CONTINUATIONS)))
    }
}
