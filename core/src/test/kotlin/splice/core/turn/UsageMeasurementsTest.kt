package splice.core.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.UsageMeasurementStamp

class UsageMeasurementsTest {
    @Test
    fun `default billing zeros do not acquire measurement presence`() {
        val usage = Usage(inputTokens = 10)
        assertEquals(0L, TurnBill.counters(usage)[PerfKeys.CACHE_WRITE_TOKENS])
        assertTrue(usage.measured.isEmpty())
        assertTrue(UsageMeasurementStamp.counters(usage).values.all { it == 0L })
        val measured = usage.copy(origin = usage.origin.copy(measured = UsageMeasure.entries.toSet()))
        assertEquals(TurnBill.counters(usage), TurnBill.counters(measured))
        assertTrue(UsageMeasurementStamp.counters(measured).values.all { it == 1L })
    }

    @Test
    fun `incremental partial contributions retain observed fields without upgrading synthesized cache zeros`() {
        val input =
            Usage(
                inputTokens = 10,
                reported = setOf(UsageField.INPUT),
                origin = UsageOrigin(measured = setOf(UsageMeasure.INPUT)),
            )
        val output =
            Usage(
                outputTokens = 5,
                reported = setOf(UsageField.OUTPUT),
                origin = UsageOrigin(measured = setOf(UsageMeasure.OUTPUT)),
            )
        val total = input + output
        assertEquals(setOf(UsageMeasure.INPUT, UsageMeasure.OUTPUT), total.measured)
        assertEquals(10L, total.inputTokens)
        assertEquals(5L, total.outputTokens)
        val unknownCache = Usage(reported = setOf(UsageField.CACHED))
        val zeroCache =
            Usage(reported = setOf(UsageField.CACHED), origin = UsageOrigin(measured = setOf(UsageMeasure.CACHE_READ)))
        assertFalse(UsageMeasure.CACHE_READ in (zeroCache + unknownCache).measured)
        assertTrue(UsageMeasure.CACHE_READ in (zeroCache + output).measured)
    }

    @Test
    fun `nonempty no request rounds intersect proof while keeping billing and reporting policy`() {
        val known = Usage(
            inputTokens = 10,
            outputTokens = 5,
            origin = UsageOrigin(measured = setOf(UsageMeasure.INPUT, UsageMeasure.OUTPUT)),
        )
        val unknown = Usage(
            outputTokens = 7,
            reported = setOf(UsageField.OUTPUT),
            origin = UsageOrigin(history = UsageHistory(request = UsageRequest.NONE)),
        )
        val appended = known.followedBy(unknown)
        val prepended = unknown.followedBy(known)
        for (result in listOf(appended, prepended)) {
            assertTrue(result.measured.isEmpty())
            assertEquals(10L, result.inputTokens)
            assertEquals(12L, result.outputTokens)
            assertEquals(known.reported, result.reported)
        }
        assertEquals(TurnBill.counters((known + unknown).copy(reported = known.reported)), TurnBill.counters(appended))
    }

    @Test
    fun `absorbed hourly and recorded quantities prevent a no request value being a proven empty identity`() {
        val known =
            Usage(inputTokens = 10, outputTokens = 5, origin = UsageOrigin(measured = UsageMeasure.entries.toSet()))
        val histories = listOf(
            AbsorbedRounds(rounds = 1, inputTokens = 20, outputTokens = 7),
            AbsorbedRounds(inputTokens = 20),
            AbsorbedRounds(cachedTokens = 3),
            AbsorbedRounds(cacheWriteTokens = 2),
            AbsorbedRounds(outputTokens = 7),
            AbsorbedRounds(cacheWriteHourlyTokens = 2),
        )
        val unproven = histories.map {
            noRequestUsage.copy(
                origin = noRequestUsage.origin.copy(history = UsageHistory(it, request = UsageRequest.NONE)),
            )
        } + listOf(
            noRequestUsage.copy(cacheWrite = CacheWrite(hourlyTokens = 2)),
            noRequestUsage.copy(reasoningTokens = 2),
            noRequestUsage.copy(origin = noRequestUsage.origin.copy(recordedOutputTokens = 2)),
        )
        for (extra in unproven) {
            val result = known.followedBy(extra)
            assertTrue(result.measured.isEmpty(), extra.toString())
            assertTrue(UsageMeasurementStamp.counters(result).values.all { it == 0L })
            assertTrue(extra.followedBy(known).measured.isEmpty(), extra.toString())
            val expected = (known + extra).copy(reported = known.reported)
            assertEquals(TurnBill.counters(expected), TurnBill.counters(result))
        }
        assertEquals(30L, TurnBill.total(TurnBill.counters(known.followedBy(unproven.first()))).requestInput)
    }

    @Test
    fun `distinct posted rounds require every portion while no request identity preserves measured facts`() {
        val known =
            Usage(inputTokens = 10, outputTokens = 5, origin = UsageOrigin(measured = UsageMeasure.entries.toSet()))
        val unknown = Usage(inputTokens = 20, outputTokens = 7)
        val combined = known.followedBy(unknown)
        assertTrue(combined.measured.isEmpty())
        assertEquals(30L, TurnBill.total(TurnBill.counters(combined)).requestInput)
        assertEquals(known.measured, noRequestUsage.followedBy(known).measured)
        assertEquals(known.measured, known.followedBy(noRequestUsage).measured)
        val local = Usage(origin = UsageOrigin(localStep = true, clientContext = known))
        assertEquals(known.measured, known.followedBy(local).measured)
        assertEquals(known.measured, local.followedBy(known).measured)
        val cut = known.copy(origin = UsageOrigin(history = UsageHistory(cutRounds = 1)))
        assertTrue(UsageMeasurementStamp.counters(cut).values.all { it == 0L })
    }
}
