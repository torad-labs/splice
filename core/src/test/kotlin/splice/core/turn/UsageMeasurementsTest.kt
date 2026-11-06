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
    fun `distinct posted rounds require every portion while no request identity preserves measured facts`() {
        val known =
            Usage(inputTokens = 10, outputTokens = 5, origin = UsageOrigin(measured = UsageMeasure.entries.toSet()))
        val unknown = Usage(inputTokens = 20, outputTokens = 7)
        val combined = known.followedBy(unknown)
        assertTrue(combined.measured.isEmpty())
        assertEquals(30L, TurnBill.total(TurnBill.counters(combined)).requestInput)
        assertEquals(known.measured, noRequestUsage.followedBy(known).measured)
        assertEquals(known.measured, known.followedBy(noRequestUsage).measured)
        val cut = known.copy(origin = UsageOrigin(history = UsageHistory(cutRounds = 1)))
        assertTrue(UsageMeasurementStamp.counters(cut).values.all { it == 0L })
    }
}
