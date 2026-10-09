package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageOrigin
import splice.core.turn.noRequestUsage

class TurnBillObservedPriceTest {
    private val rates = ModelRates(2.0, 0.2, 10.0, cacheWrite = 2.5)

    @Test
    fun `a price cannot replace absent cache buckets with measured zeros`() {
        val reported = setOf(UsageField.INPUT, UsageField.OUTPUT)
        val counters = TurnBill.counters(Usage(inputTokens = 100, outputTokens = 7, reported = reported))
        assertNull(TurnBill.usd(counters, rates))
    }

    @Test
    fun `missing cache-write usage makes an otherwise reported price unknown`() {
        val reported = setOf(UsageField.INPUT, UsageField.OUTPUT, UsageField.CACHED)
        val counters = TurnBill.counters(Usage(inputTokens = 100, outputTokens = 7, reported = reported))
        assertNull(TurnBill.usd(counters, rates))
    }

    @Test
    fun `input-only normalized group retains its enforceable charge`() {
        val fields = UsageField.entries.toSet() - UsageField.OUTPUT
        val row = TurnBill.counters(Usage(inputTokens = 100, cachedTokens = 20, reported = fields))
        assertNull(TurnBill.usd(row, rates))
        assertEquals(0.000164, TurnBill.lowerBoundUsd(row, rates)!!, 1e-12)
    }

    @Test
    fun `known output with no input group still has a lower bound`() {
        val row = TurnBill.counters(Usage(outputTokens = 7, reported = setOf(UsageField.OUTPUT)))
        assertEquals(0.00007, TurnBill.lowerBoundUsd(row, rates)!!, 1e-12)
        assertNull(TurnBill.usd(row, rates))
    }

    @Test
    fun `an incomplete inclusive input group has no safe charge`() {
        val row = TurnBill.counters(Usage(inputTokens = 100, reported = setOf(UsageField.INPUT)))
        assertNull(TurnBill.lowerBoundUsd(row, rates))
    }

    @Test
    fun `a lower bound never exceeds the fully reported charge`() {
        for (input in listOf(0L, 100L, 300_000L)) {
            val full = Usage(inputTokens = input, outputTokens = 7, cachedTokens = input / 4)
            val exact = TurnBill.usd(TurnBill.counters(full), rates)!!
            val observations = listOf(
                emptySet(),
                setOf(UsageField.OUTPUT),
                UsageField.entries.toSet() - UsageField.OUTPUT,
            )
            for (fields in observations) {
                val bound = TurnBill.lowerBoundUsd(TurnBill.counters(full.copy(reported = fields)), rates)!!
                assertTrue(bound <= exact, "fields=$fields input=$input bound=$bound exact=$exact")
            }
        }
    }

    @Test
    fun `output with unknown input cannot assume either declared tier`() {
        val tiered = ModelRates(2.0, 0.2, 10.0, longContext = LongContextRates(100, 1.0, 0.1, 5.0))
        val row = TurnBill.counters(Usage(outputTokens = 7, reported = setOf(UsageField.OUTPUT)))
        assertEquals(0.000035, TurnBill.lowerBoundUsd(row, tiered)!!, 1e-12)
    }

    @Test
    fun `a partial bill never overcharges absorbed requests that straddle a tier`() {
        val tiered = ModelRates(
            2.0,
            0.2,
            10.0,
            longContext = LongContextRates(120_000L, 4.0, 0.4, 15.0),
        )
        val row = TurnBill.counters(
            Usage(
                origin = UsageOrigin(history = UsageHistory(absorbed = AbsorbedRounds(2, 300_000, 0, 0, 0))),
                reported = emptySet(),
            ),
        )
        val actualKnownSpend = TokenCost().of(TokenBuckets(input = 100_000), tiered) +
            TokenCost().of(TokenBuckets(input = 200_000), tiered)
        assertTrue(TurnBill.lowerBoundUsd(row, tiered)!! <= actualKnownSpend)
    }

    @Test
    fun `no request is an exact zero bill without any measured token fields`() {
        val row = TurnBill.counters(noRequestUsage.copy())
        assertEquals(1L, row[PerfKeys.NO_REQUEST])
        assertEquals(noRequestUsage.origin.history, TurnBill.history(row))
        assertTrue(TurnBill.fullyReported(row))
        assertEquals(0.0, TurnBill.usd(row, rates))
        val tokenKeys = setOf(
            PerfKeys.IN_TOKENS,
            PerfKeys.OUT_TOKENS,
            PerfKeys.CACHED_TOKENS,
            PerfKeys.CACHE_WRITE_TOKENS,
        )
        assertTrue(row.keys.none { it in tokenKeys })
        assertFalse(TurnBill.fullyReported(TurnBill.counters(Usage(reported = emptySet()))))
    }

    @Test
    fun `an empty no-request bill needs no rate card or model id`() {
        val row = TurnBill.counters(noRequestUsage)
        val price = TurnPrice(null)
        assertEquals(0.0, price.usd(null, row))
        assertEquals(0.0, price.lowerBoundUsd("synthetic-no-card", row))
    }

    @Test
    fun `explicit refusal ownership is counted without counting local code-mode steps`() {
        val refusal = TurnBill.counters(noRequestUsage)
        assertTrue(TurnBill.isCounted(refusal))
        assertFalse(TurnBill.isCounted(refusal + (PerfKeys.LOCAL_STEP to 1L)))
        assertFalse(TurnBill.isCounted(emptyMap()))
        val websocket = mapOf(PerfKeys.ATTEMPTS to 0L, PerfKeys.TRANSPORT_ATTEMPT_STARTS to 1L)
        assertTrue(TurnBill.isCounted(websocket))
    }

    @Test
    fun `observed posted zeros still need a rate card`() {
        val price = TurnPrice(null)
        val row = TurnBill.counters(Usage())
        assertNull(price.usd("synthetic-no-card", row))
        assertNull(price.lowerBoundUsd("synthetic-no-card", row))
    }

    @Test
    fun `absorbed or cut no-request history cannot bypass pricing without rates`() {
        val histories = listOf(
            noRequestUsage.origin.history.copy(absorbed = AbsorbedRounds(rounds = 1)),
            noRequestUsage.origin.history.copy(cutRounds = 1),
        )
        for (history in histories) {
            val row = TurnBill.counters(noRequestUsage.copy(origin = noRequestUsage.origin.copy(history = history)))
            assertNull(TurnPrice(null).usd(null, row))
            assertNull(TurnPrice(null).lowerBoundUsd(null, row))
        }
    }

    @Test
    fun `no request cannot hide an earlier missing report`() {
        val cut = noRequestUsage.copy(
            origin = noRequestUsage.origin.copy(history = noRequestUsage.origin.history.copy(cutRounds = 1)),
        )
        val row = TurnBill.counters(cut)
        assertFalse(TurnBill.fullyReported(row))
        assertNull(TurnBill.usd(row, rates))
    }

    @Test
    fun `no final request retains measured absorbed spend instead of forcing zero`() {
        val usage = noRequestUsage.copy(
            outputTokens = 7,
            origin = noRequestUsage.origin.copy(
                history = noRequestUsage.origin.history.copy(absorbed = AbsorbedRounds(1, 100, 20, 0, 7)),
            ),
        )
        val row = TurnBill.counters(usage)
        assertTrue(TurnBill.fullyReported(row))
        assertEquals(0.000234, TurnBill.usd(row, rates)!!, 1e-12)
    }

    @Test
    fun `all observed zeros remain a known zero price`() {
        assertEquals(0.0, TurnBill.usd(TurnBill.counters(Usage()), rates))
    }
}
