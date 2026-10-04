// NEW: a perf row bills its final round and every round it absorbed, each priced once.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import kotlin.math.abs

private const val TIER_OVER = 120_000L
private const val CENT_FRACTION = 1e-12

class TurnBillTest {
    private val cost = TokenCost()
    private val rates = ModelRates(
        input = 2.0,
        cacheRead = 0.2,
        output = 10.0,
        cacheWrite = 2.5,
        longContext = LongContextRates(overInputTokens = TIER_OVER, input = 4.0, cacheRead = 0.4, output = 15.0),
    )

    @Test
    fun `a row written before the absorbed counters prices exactly as it did`() {
        val row = mapOf(
            PerfKeys.IN_TOKENS to 150_000L,
            PerfKeys.CACHED_TOKENS to 90_000L,
            PerfKeys.CACHE_WRITE_TOKENS to 4_000L,
            PerfKeys.OUT_TOKENS to 2_000L,
        )
        val buckets = TokenBuckets(input = 56_000, cacheRead = 90_000, cacheWrite = 4_000, output = 2_000)
        val before = cost.of(buckets, rates)
        assertEquals(before, TurnBill.usd(row, rates), CENT_FRACTION)
        assertEquals(AbsorbedRounds(), TurnBill.absorbed(row))
    }

    @Test
    fun `a turn writes the absorbed counters only when it absorbed a round`() {
        val single = TurnBill.counters(Usage(inputTokens = 10, outputTokens = 2))
        assertTrue(single.keys.none { it.startsWith("absorbed_") }, "a one-round turn adds no keys: $single")

        val rounds =
            AbsorbedRounds(rounds = 2, inputTokens = 30, cachedTokens = 5, cacheWriteTokens = 1, outputTokens = 4)
        val row = TurnBill.counters(Usage(inputTokens = 20, outputTokens = 9, absorbed = rounds))
        assertEquals(rounds, TurnBill.absorbed(row), "the row reads back what the turn wrote")
        assertEquals(9L, row[PerfKeys.OUT_TOKENS], "out_tokens stays the turn's whole output")
    }

    @Test
    fun `each absorbed round is priced as a request of its own, at its own tier`() {
        val early = Usage(inputTokens = 100_000, outputTokens = 300, cachedTokens = 60_000)
        val final = Usage(inputTokens = 150_000, outputTokens = 500, cachedTokens = 90_000)
        val row = TurnBill.counters(final.copy(outputTokens = 800, absorbed = early.finalRound))

        val separately = TurnBill.usd(TurnBill.counters(early), rates) + TurnBill.usd(TurnBill.counters(final), rates)
        assertEquals(separately, TurnBill.usd(row, rates), CENT_FRACTION)
        assertTrue(separately < cost.of(TurnBill.total(row), rates), "one summed request would price at the tier")
    }

    @Test
    fun `absorbed rounds on one side of a tier price exactly, and straddling ones within the tier premium`() {
        val small = listOf(round(50_000), round(60_000))
        assertEquals(separate(small), TurnBill.usd(absorbing(small), rates), CENT_FRACTION, "both under the tier")

        val straddling = listOf(round(100_000), round(200_000))
        val absorbed = TurnBill.absorbed(absorbing(straddling))
        val premium = (4.0 - 2.0) * absorbed.inputTokens + (15.0 - 10.0) * absorbed.outputTokens
        val error = abs(TurnBill.usd(absorbing(straddling), rates) - separate(straddling))
        assertTrue(error > 0.0, "the mean of 150k prices the 100k round at the tier")
        assertTrue(error <= premium / 1_000_000.0, "error $error is within the tier premium on the absorbed buckets")
    }

    /** A row whose final round measured nothing, carrying [rounds] as its absorbed rounds. */
    private fun absorbing(rounds: List<Usage>): Map<String, Long> =
        TurnBill.counters(
            Usage(
                outputTokens = rounds.sumOf(Usage::outputTokens),
                absorbed = rounds.map(Usage::finalRound).reduce(AbsorbedRounds::plus),
            ),
        )

    private fun separate(rounds: List<Usage>): Double = rounds.sumOf { TurnBill.usd(TurnBill.counters(it), rates) }

    private fun round(input: Long) = Usage(inputTokens = input, outputTokens = 100)
}
