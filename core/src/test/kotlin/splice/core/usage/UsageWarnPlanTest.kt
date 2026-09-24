// NEW: console review 2026-09-24 — warn reads the plan windows. A subscription head carries no
// ratelimit token headers, so before the plan tier its warn said `ok`/`none` at 99% of a 7d window;
// these arms pin which window speaks, that a window past its reset is silent, and that the worse of
// two real signals wins.
package splice.core.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsageWarnPlanTest {

    private val now = 1_788_000_000L
    private val hourFromNow = now + 3_600
    private val hourAgo = now - 3_600

    private fun plan(fiveHour: QuotaWindowView? = null, sevenDay: QuotaWindowView? = null) =
        PlanWindows(QuotaView(fiveHour, sevenDay, plan = "max"), now)

    @Test
    fun `a subscription head at 99 percent of its 7d window is critical, and says which window`() {
        val warn = UsageWarnPolicy.computeUsageWarn(
            plan = plan(QuotaWindowView(40, hourFromNow), QuotaWindowView(99, hourFromNow)),
        )
        assertEquals(UsageWarn("critical", 99, "quota_7d", "2026-08-29T11:40:00Z"), warn)
    }

    @Test
    fun `the fuller live window reaches warn at the knob's percentage`() {
        val warn = UsageWarnPolicy.computeUsageWarn(
            warnPct = 80,
            plan = plan(QuotaWindowView(85, hourFromNow), QuotaWindowView(60, hourFromNow)),
        )
        assertEquals("warn", warn.level)
        assertEquals("quota_5h", warn.source)
        assertEquals(85, warn.pct)
    }

    @Test
    fun `a window whose reset has passed is history, not a limit`() {
        // live claude-muse, 2026-09-24: 99% of a 7d window that reset 3.7 days earlier
        val warn = UsageWarnPolicy.computeUsageWarn(
            plan = plan(QuotaWindowView(30, hourFromNow), QuotaWindowView(99, hourAgo)),
        )
        assertEquals(UsageWarn("ok", 30, "quota_5h", "2026-08-29T11:40:00Z"), warn)

        val allSpent = UsageWarnPolicy.computeUsageWarn(
            plan = plan(QuotaWindowView(99, hourAgo), QuotaWindowView(99, now)),
        )
        assertEquals("none", allSpent.source, "a window resetting AT now has reset")
    }

    @Test
    fun `a window with no reset instant is still live`() {
        val warn = UsageWarnPolicy.computeUsageWarn(plan = plan(sevenDay = QuotaWindowView(90, null)))
        assertEquals(UsageWarn("warn", 90, "quota_7d", null), warn)
    }

    @Test
    fun `the worse of the ratelimit and plan signals wins`() {
        val halfSpentHeaders = RateLimitState(1000, 500, "6m0s")
        val planWarns = UsageWarnPolicy.computeUsageWarn(
            ratelimit = halfSpentHeaders,
            plan = plan(QuotaWindowView(90, hourFromNow)),
        )
        assertEquals("quota_5h", planWarns.source)
        assertEquals("warn", planWarns.level)

        val headersCritical = UsageWarnPolicy.computeUsageWarn(
            ratelimit = RateLimitState(1000, 0, "6m0s"),
            plan = plan(QuotaWindowView(90, hourFromNow)),
        )
        assertEquals("ratelimit", headersCritical.source)
        assertEquals("critical", headersCritical.level)

        val bothOkHigherPctSpeaks = UsageWarnPolicy.computeUsageWarn(
            ratelimit = halfSpentHeaders,
            plan = plan(QuotaWindowView(20, hourFromNow)),
        )
        assertEquals("ratelimit", bothOkHigherPctSpeaks.source)
        assertEquals(50, bothOkHigherPctSpeaks.pct)
    }

    @Test
    fun `a plan signal outranks the 5h token fallback`() {
        val warn = UsageWarnPolicy.computeUsageWarn(
            outputTokens5h = 120,
            warnTokens5h = 100,
            plan = plan(QuotaWindowView(10, hourFromNow)),
        )
        assertEquals("quota_5h", warn.source)
        assertEquals("ok", warn.level)
    }

    @Test
    fun `warnPct 0 silences the plan tier's warn but never its critical edge`() {
        val quiet = UsageWarnPolicy.computeUsageWarn(warnPct = 0, plan = plan(QuotaWindowView(90, hourFromNow)))
        assertEquals("ok", quiet.level)
        val edge = UsageWarnPolicy.computeUsageWarn(warnPct = 0, plan = plan(QuotaWindowView(98, hourFromNow)))
        assertEquals("critical", edge.level)
    }
}
