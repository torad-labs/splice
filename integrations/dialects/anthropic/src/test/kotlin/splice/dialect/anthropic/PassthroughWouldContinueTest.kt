// What the live listing asks of this dialect's re-anchor rule: would a failure of the round, as it stands now,
// still be continued? Each refusal the rule makes at failure time reads false here, and the true case reads true.
package splice.dialect.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.DEFAULT_MAX_CONTINUATIONS
import splice.upstream.LiveRound

class PassthroughWouldContinueTest {
    private val resumable = PassthroughReanchorPolicy(prefill = true)

    private fun round(attempt: Int = 0, tool: Boolean = false, text: Boolean = false) =
        LiveRound(attempt, toolOpened = tool, textWritten = text)

    @Test
    fun `a round with prose only would be continued on an upstream that continues from a prefill`() {
        assertEquals(true, resumable.wouldContinue(round()))
        assertEquals(true, resumable.wouldContinue(round(text = true)))
    }

    @Test
    fun `a round that has opened a tool call would not be`() {
        assertEquals(false, resumable.wouldContinue(round(tool = true)))
        assertEquals(false, resumable.wouldContinue(round(tool = true, text = true)))
    }

    @Test
    fun `a spent continuation budget would not be`() {
        assertEquals(true, resumable.wouldContinue(round(attempt = DEFAULT_MAX_CONTINUATIONS - 1)))
        assertEquals(false, resumable.wouldContinue(round(attempt = DEFAULT_MAX_CONTINUATIONS)))
    }

    @Test
    fun `prose shown on an upstream not measured to continue from a prefill would not be, a bare restart would`() {
        val unmeasured = PassthroughReanchorPolicy(prefill = false)
        assertEquals(false, unmeasured.wouldContinue(round(text = true)))
        assertEquals(true, unmeasured.wouldContinue(round()), "nothing shown: the whole request restarts")
    }
}
