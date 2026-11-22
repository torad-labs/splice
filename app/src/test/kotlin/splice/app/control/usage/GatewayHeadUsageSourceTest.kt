// Verifies that when a custom head (e.g. `claude-kimi`) launches through the Splice gateway onto a differently-named
// provider (`kimi`), its usage snapshot merges pre-upgrade head readings with live gateway provider readings.
package splice.app.control.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView

internal class GatewayHeadUsageSourceTest {
    @Test
    fun `keeps pre-upgrade head reading before any gateway turn and merges fresher gateway quota afterward`() {
        val preUpgrade = UsageView(
            outputTokens5h = 7L,
            entries = 1,
            ratelimit = RateLimitView(1000L, 900L, "5m", observedAt = 1_000L),
            quota = QuotaView(
                fiveHour = QuotaWindowView(usedPct = 42, resetsAt = 10_000L, observedAt = 1_000L),
                sevenDay = null,
                plan = "kimi",
            ),
        )
        var gatewayView = UsageView(outputTokens5h = 0L, entries = 0, ratelimit = null, quota = null)
        val merged = GatewayHeadUsageSource(
            head = HeadUsageSource { preUpgrade },
            gateway = GatewayProviderUsage { HeadUsageSource { gatewayView } },
        )

        val before = merged.snapshot()
        assertEquals(7L, before.outputTokens5h)
        assertEquals(1, before.entries)
        assertEquals(42, before.quota?.fiveHour?.usedPct)

        gatewayView = UsageView(
            outputTokens5h = 12L,
            entries = 1,
            ratelimit = RateLimitView(1000L, 800L, "4m", observedAt = 2_000L),
            quota = QuotaView(
                fiveHour = QuotaWindowView(usedPct = 77, resetsAt = 10_000L, observedAt = 2_000L),
                sevenDay = null,
                plan = "kimi",
            ),
        )
        val after = merged.snapshot()
        assertEquals(19L, after.outputTokens5h)
        assertEquals(2, after.entries)
        assertEquals(77, after.quota?.fiveHour?.usedPct)
        assertEquals(800L, after.ratelimit?.remainingTokens)
    }

    @Test
    fun `returns head snapshot unchanged when no separate gateway provider source exists`() {
        val headOnly = UsageView(outputTokens5h = 5L, entries = 1, ratelimit = null, quota = null)
        val merged = GatewayHeadUsageSource(
            head = HeadUsageSource { headOnly },
            gateway = GatewayProviderUsage { null },
        )
        assertEquals(headOnly, merged.snapshot())
    }
}
