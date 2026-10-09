// NEW: the context a code-mode step with no round of its own reports to Claude Code, per conversation.
package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageOrigin
import splice.provider.codex.stream.CodeModeClientContexts

internal class CodeModeClientContextsTest {
    private val step = TurnOutcome.Success(hasToolUse = true, incomplete = false, usage = Usage(
        origin = UsageOrigin(localStep = true),
    ))

    private val contextFields = UsageField.entries.toSet() - UsageField.OUTPUT

    private fun contextOf(outcome: TurnOutcome): Usage? = (outcome as TurnOutcome.Success).usage.origin.clientContext

    @Test
    fun `a step that measured nothing carries the newest measured context, and its own usage stays zero`() {
        val contexts = CodeModeClientContexts { null }
        contexts.report("a", TurnOutcome.Success(true, false, Usage(1_000, 40, 600, 9, 50)))
        contexts.report("a", TurnOutcome.Success(true, false, Usage(1_200, 30, 700, 0, 0)))

        val reported = contexts.report("a", step) as TurnOutcome.Success
        val expected = Usage(inputTokens = 1_200, cachedTokens = 700, reported = contextFields)
        assertEquals(expected, reported.usage.origin.clientContext)
        assertEquals(Usage(
            origin = UsageOrigin(localStep = true),
        ), reported.usage.run { copy(origin = origin.copy(clientContext = null)) }, "accounting stays raw")
    }

    @Test
    fun `a measured round passes through untouched`() {
        val contexts = CodeModeClientContexts { null }
        contexts.report("a", TurnOutcome.Success(true, false, Usage(1_000, 40, 600)))
        val measured = TurnOutcome.Success(true, false, Usage(800, 5, 0))
        assertSame(measured, contexts.report("a", measured))
    }

    @Test
    fun `zero until a round of the conversation reports, and conversations never share a context`() {
        val contexts = CodeModeClientContexts { null }
        assertNull(contextOf(contexts.report("a", step)), "no round has reported")
        contexts.report("a", TurnOutcome.Success(true, false, Usage(outputTokens = 3)))
        assertNull(contextOf(contexts.report("a", step)), "a round with no input measured nothing")
        contexts.report("b", TurnOutcome.Success(true, false, Usage(500, 1, 100)))
        assertNull(contextOf(contexts.report("a", step)), "another conversation's round is not this one's")
    }

    @Test
    fun `a failed round's salvage and a finished source round are each the newest context`() {
        val contexts = CodeModeClientContexts { null }
        val failure = TurnOutcome.Failure(
            "torn",
            cause = FailureCause.UPSTREAM_STALLED,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = Usage(900, 2, 300),
        )
        assertSame(failure, contexts.report("a", failure), "a failure reaches the client unchanged")
        val failedContext = Usage(inputTokens = 900, cachedTokens = 300, reported = contextFields)
        assertEquals(failedContext, contextOf(contexts.report("a", step)))

        contexts.note("a", Usage(1_500, 80, 1_000, 0, 20))
        assertEquals(
            Usage(inputTokens = 1_500, cachedTokens = 1_000, cacheWriteTokens = 20, reported = contextFields),
            contextOf(contexts.report("a", step)),
        )
    }

    @Test
    fun `after a restart the newest round its records kept is the context`() {
        val asked = mutableListOf<String>()
        val contexts = CodeModeClientContexts { key ->
            asked += key
            Usage(2_000, 50, 1_800)
        }
        val persisted = Usage(inputTokens = 2_000, cachedTokens = 1_800, reported = contextFields)
        assertEquals(persisted, contextOf(contexts.report("a", step)))
        contexts.report("a", TurnOutcome.Success(true, false, Usage(2_100, 4, 1_900)))
        val newest = Usage(inputTokens = 2_100, cachedTokens = 1_900, reported = contextFields)
        assertEquals(newest, contextOf(contexts.report("a", step)))
        assertEquals(listOf("a"), asked, "the records are read only while this process has measured nothing")
    }
}
