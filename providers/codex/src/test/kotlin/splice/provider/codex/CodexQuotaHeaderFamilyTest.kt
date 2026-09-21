// NEW: x-codex header family owned by :providers-codex, sorted by window length.
package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.usage.QuotaHeaderRead
import splice.core.util.WallClock

class CodexQuotaHeaderFamilyTest {

    private val now = 1_788_000_000_000L
    private val family = CodexQuotaHeaderFamily()
    private val clock = WallClock { now }

    private fun read(map: Map<String, String>) = QuotaHeaderRead { map[it] }

    @Test
    fun `the x-codex family sorts windows by length and accepts both reset spellings`() {
        val plus = mapOf(
            "x-codex-primary-used-percent" to "31.5",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-after-seconds" to "3600",
            "x-codex-secondary-used-percent" to "12",
            "x-codex-secondary-window-minutes" to "10080",
            "x-codex-secondary-reset-at" to "1788500000000",
            "x-codex-plan-type" to "plus",
        )
        val snapshot = family.snapshot(read(plus), clock)!!
        assertEquals(31.5, snapshot.fiveHour!!.usedPercent, 1e-9)
        assertEquals(now / 1000 + 3600, snapshot.fiveHour!!.resetsAt, "reset-after-seconds is relative to now")
        assertEquals(12.0, snapshot.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_500_000L, snapshot.sevenDay!!.resetsAt, "a millisecond epoch is normalized to seconds")
        assertEquals("plus", snapshot.plan)

        val weeklyOnly = mapOf("x-codex-primary-used-percent" to "30", "x-codex-primary-window-minutes" to "10080")
        val pro = family.snapshot(read(weeklyOnly), clock)!!
        assertNull(pro.fiveHour)
        assertEquals(30.0, pro.sevenDay!!.usedPercent, 1e-9)
        val perMinute = family.snapshot(read(mapOf("x-ratelimit-limit-tokens" to "1000")), clock)
        assertNull(perMinute, "per-minute families are not quota windows")
    }

    @Test
    fun `resets_at at 1e11 and above is milliseconds`() {
        val millis = mapOf(
            "x-codex-primary-used-percent" to "1",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "100000000001",
        )
        val snapshot = family.snapshot(read(millis), clock)!!
        assertEquals(100_000_000L, snapshot.fiveHour!!.resetsAt)
        val seconds = mapOf(
            "x-codex-primary-used-percent" to "1",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1788010000",
        )
        assertEquals(1_788_010_000L, family.snapshot(read(seconds), clock)!!.fiveHour!!.resetsAt)
    }
}
