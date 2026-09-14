// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadPerfSource
import splice.control.HeadUsageSource
import splice.control.QuotaView
import splice.control.QuotaWindowView
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.usage.QuotaSnapshot
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore

public class UsageStoreSource(
    private val store: UsageStore,
    private val quota: QuotaTracker? = null,
) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit, quota?.snapshot()?.let(::quotaView))
    }

    private fun quotaView(snapshot: QuotaSnapshot): QuotaView = QuotaView(
        fiveHour = snapshot.fiveHour?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt) },
        sevenDay = snapshot.sevenDay?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt) },
        plan = snapshot.plan,
    )
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        val tail = s.tail.map { row -> row.mapValues { (_, v) -> v.toString() } }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

public class PerfStatsSource(private val stats: PerfStats) : HeadPerfSource {
    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)
}
