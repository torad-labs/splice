package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField

class CodeModeUsageObservationTest {
    private fun success(usage: Usage) = TurnOutcome.Success(false, false, usage)

    @Test
    fun `hidden rounds without usage cannot manufacture observed token zeros`() {
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(Usage(reported = emptySet())))
        val result = rounds.finish(success(Usage(reported = emptySet()))) as TurnOutcome.Success
        assertTrue(TurnBill.counters(result.usage).isEmpty())
    }

    @Test
    fun `a content-only terminal cannot add input observations to an output-only prefix`() {
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(Usage(outputTokens = 3, reported = setOf(UsageField.OUTPUT))))
        val failure = TurnOutcome.Failure(
            "synthetic",
            cause = FailureCause.UPSTREAM_REPORTED,
            phase = FailurePhase.MID_OUTPUT,
        )
        val result = rounds.finish(failure) as TurnOutcome.Failure
        assertEquals(mapOf(PerfKeys.OUT_TOKENS to 3L), TurnBill.counters(result.salvagedUsage))
        assertEquals(mapOf(PerfKeys.OUT_TOKENS to 3L), TurnBill.counters(result.partial!!.usage))
    }

    @Test
    fun `reported terminal zero supersedes the prior input and bills that prior request once`() {
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 20)))
        val terminal = TurnOutcome.ClientAbandoned(salvagedUsage = Usage())
        val result = rounds.finish(terminal) as TurnOutcome.ClientAbandoned
        assertEquals(0L, result.salvagedUsage.inputTokens)
        assertEquals(0L, result.salvagedUsage.cachedTokens)
        assertEquals(1L, result.salvagedUsage.absorbed.rounds)
        assertEquals(100L, result.salvagedUsage.absorbed.inputTokens)
    }
}
