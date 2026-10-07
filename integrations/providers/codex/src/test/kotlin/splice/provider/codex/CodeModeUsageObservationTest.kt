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
import splice.core.turn.noRequestUsage

class CodeModeUsageObservationTest {
    private fun success(usage: Usage) = TurnOutcome.Success(false, false, usage)

    @Test
    fun `local callbacks and protocol failures preserve the posted prefix without inventing another request`() {
        val prefix = Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 20)
        val endings = listOf(
            success(Usage(localStep = true)),
            TurnOutcome.Failure(
                "synthetic local",
                cause = FailureCause.CODE_MODE_PROTOCOL,
                phase = FailurePhase.MID_OUTPUT,
                salvagedUsage = noRequestUsage,
            ),
        )
        for (ending in endings) {
            val rounds = CodeModeOutcomeAccumulator()
            rounds.absorb(success(prefix))
            val result = rounds.finishLocal(ending)
            val usage = when (result) {
                is TurnOutcome.Success -> result.usage
                is TurnOutcome.Failure -> {
                    assertEquals(null, result.partial)
                    result.salvagedUsage
                }
                is TurnOutcome.ClientAbandoned -> result.salvagedUsage
            }
            assertEquals(TurnBill.counters(prefix), TurnBill.counters(usage))
            assertEquals(0L, usage.absorbed.rounds)
            assertEquals(0L, usage.cutRounds)
        }
    }

    @Test
    fun `a local measured zero cannot complete an unreported upstream bill`() {
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(Usage(reported = emptySet())))
        val result = rounds.finishLocal(success(Usage(localStep = true))) as TurnOutcome.Success
        assertTrue(TurnBill.counters(result.usage).isEmpty())
    }

    @Test
    fun `a local ending without a prefix cannot own a posted request`() {
        val rounds = CodeModeOutcomeAccumulator()
        val result = rounds.finishLocal(success(noRequestUsage)) as TurnOutcome.Success
        assertEquals(noRequestUsage, result.usage)
    }

    @Test
    fun `local reanchor suppression cannot erase a posted abandonment's missing bill`() {
        val prior = Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 20)
        val ending = TurnOutcome.ClientAbandoned()
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(prior))
        val result = rounds.finishLocal(ending) as TurnOutcome.ClientAbandoned
        assertEquals(prior.finalRound, result.salvagedUsage.absorbed)
        assertTrue(PerfKeys.IN_TOKENS !in TurnBill.counters(result.salvagedUsage))
        assertEquals(ending.salvagedUsage.history.request, result.salvagedUsage.history.request)
    }

    @Test
    fun `a consumed source owns no missing bill beside the next posted request`() {
        val rounds = CodeModeOutcomeAccumulator()
        rounds.absorb(success(noRequestUsage.copy(outputTokens = 0)))
        val next = Usage(inputTokens = 100, outputTokens = 7)
        val result = rounds.finish(success(next)) as TurnOutcome.Success
        assertEquals(next, result.usage)
        assertEquals(0L, result.usage.cutRounds)
        assertEquals(0L, result.usage.absorbed.rounds)
    }

    @Test
    fun `an already counted source cut cannot become another missing final request`() {
        val rounds = CodeModeOutcomeAccumulator()
        val cut = noRequestUsage.copy(history = noRequestUsage.history.copy(cutRounds = 1))
        rounds.absorb(success(cut))
        val result = rounds.finish(success(Usage(inputTokens = 100, outputTokens = 7))) as TurnOutcome.Success
        assertEquals(1L, result.usage.cutRounds)
        assertEquals(0L, result.usage.absorbed.rounds)
        assertEquals(100L, result.usage.inputTokens)
    }

    @Test
    fun `no-request endings preserve either a known or unknown posted final round`() {
        for (prefix in listOf(Usage(inputTokens = 100, outputTokens = 7), Usage(reported = emptySet()))) {
            val rounds = CodeModeOutcomeAccumulator()
            rounds.absorb(success(prefix))
            val result = rounds.finish(success(noRequestUsage)) as TurnOutcome.Success
            assertEquals(prefix, result.usage)
        }
    }

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
