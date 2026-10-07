package splice.head.round

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.noRequestUsage

class RoundUsageUnknownContinuationTest {
    private val rates = ModelRates(2.0, 0.2, 10.0)

    @Test
    fun `no-request values are identities before and after a posted round`() {
        for (posted in listOf(Usage(inputTokens = 100, outputTokens = 7), Usage(reported = emptySet()), Usage())) {
            val total = RoundUsage().plusRound(noRequestUsage).plusRound(posted)
                .plusTerminal(noRequestUsage.copy(outputTokens = 0)).toUsage()
            assertEquals(posted, total)
        }
    }

    @Test
    fun `a genuinely local round keeps its explicit known zero bill`() {
        val row = TurnBill.counters(RoundUsage().plusRound(Usage(localStep = true)).toUsage())
        assertEquals(0.0, TurnBill.usd(row, rates))
    }

    @Test
    fun `a local zero cannot invent an output observation for a later real request`() {
        val usage = RoundUsage().plusRound(Usage(localStep = true))
            .plusRound(Usage(inputTokens = 100, reported = UsageField.entries.toSet() - UsageField.OUTPUT)).toUsage()
        assertNull(TurnBill.counters(usage)[splice.core.perf.PerfKeys.OUT_TOKENS])
        assertNull(TurnBill.usd(TurnBill.counters(usage), rates))
    }

    @Test
    fun `an unreported continuation absorbs known input once without inheriting its bill`() {
        val usage = RoundUsage().plusRound(Usage(inputTokens = 100, outputTokens = 7))
            .plusRound(Usage(reported = emptySet())).toUsage()
        val row = TurnBill.counters(usage)
        assertEquals(100L, TurnBill.total(row).input)
        assertNull(TurnBill.usd(row, rates))
    }

    @Test
    fun `known output from a prefix never makes an input-only continuation exact`() {
        val first = Usage(inputTokens = 100, outputTokens = 7)
        val next = Usage(inputTokens = 120, reported = UsageField.entries.toSet() - UsageField.OUTPUT)
        val row = TurnBill.counters(RoundUsage().plusRound(first).plusTerminal(next).toUsage())
        assertEquals(220L, TurnBill.total(row).input)
        assertEquals(7L, TurnBill.total(row).output)
        assertNull(TurnBill.usd(row, rates))
    }

    @Test
    fun `an unknown earlier bill cannot disappear when a later round reports`() {
        val row = TurnBill.counters(
            RoundUsage().plusRound(Usage(reported = emptySet()))
                .plusRound(Usage(inputTokens = 100, outputTokens = 7)).toUsage(),
        )
        assertEquals(100L, TurnBill.total(row).input)
        assertNull(TurnBill.usd(row, rates))
    }

    @Test
    fun `an unreported terminal absorbs known input once and leaves its request unknown`() {
        val usage = RoundUsage().plusRound(Usage(inputTokens = 100, outputTokens = 7))
            .plusTerminal(Usage(reported = emptySet())).toUsage()
        val row = TurnBill.counters(usage)
        assertEquals(100L, TurnBill.total(row).input)
        assertNull(TurnBill.usd(row, rates))
    }
}
