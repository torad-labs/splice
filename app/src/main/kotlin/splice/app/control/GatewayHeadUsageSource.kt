// Combines a custom head's own persisted usage/quota with its provider's gateway usage/quota when the head's key
// differs from its provider's key (e.g. `[heads.claude-kimi]` serving `[providers.kimi]`). Since head-catalog (#735)
// launches custom heads through the Splice gateway (`/door`), turns record against the provider's gateway lane while
// pre-upgrade readings remain on the head's own files; merging keeps both retention and live readings accurate.
package splice.app.control

import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView

internal fun interface GatewayProviderUsage {
    operator fun invoke(): HeadUsageSource?
}

internal class GatewayHeadUsageSource(
    private val head: HeadUsageSource,
    private val gateway: GatewayProviderUsage,
) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val own = head.snapshot()
        val provider = gateway()?.snapshot() ?: return own
        return UsageView(
            outputTokens5h = own.outputTokens5h + provider.outputTokens5h,
            entries = own.entries + provider.entries,
            ratelimit = fresherRateLimit(own.ratelimit, provider.ratelimit),
            quota = mergeQuota(own.quota, provider.quota),
        )
    }

    override suspend fun probeNow() {
        head.probeNow()
        gateway()?.probeNow()
    }

    private fun fresherRateLimit(own: RateLimitView?, provider: RateLimitView?): RateLimitView? = when {
        own == null -> provider
        provider == null -> own
        (provider.observedAt ?: Long.MIN_VALUE) >= (own.observedAt ?: Long.MIN_VALUE) -> provider
        else -> own
    }

    private fun mergeQuota(own: QuotaView?, provider: QuotaView?): QuotaView? = when {
        own == null -> provider
        provider == null -> own
        else -> QuotaView(
            fiveHour = fresherWindow(own.fiveHour, provider.fiveHour),
            sevenDay = fresherWindow(own.sevenDay, provider.sevenDay),
            plan = provider.plan ?: own.plan,
            noUsageAt = listOfNotNull(own.noUsageAt, provider.noUsageAt).maxOrNull(),
        )
    }

    private fun fresherWindow(own: QuotaWindowView?, provider: QuotaWindowView?): QuotaWindowView? = when {
        own == null -> provider
        provider == null -> own
        (provider.observedAt ?: Long.MIN_VALUE) >= (own.observedAt ?: Long.MIN_VALUE) -> provider
        else -> own
    }
}
