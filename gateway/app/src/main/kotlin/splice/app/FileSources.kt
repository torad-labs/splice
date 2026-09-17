// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import splice.control.CompactView
import splice.control.EconomicsRow
import splice.control.HeadCompactSource
import splice.control.HeadEconomicsSource
import splice.control.HeadPerfSkipSource
import splice.control.HeadPerfSource
import splice.control.HeadSessionPerfSource
import splice.control.HeadUsageSource
import splice.control.QuotaView
import splice.control.QuotaWindowView
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.usage.QuotaSnapshot
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
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

/** The hourly quota rollup, projected onto the control plane's row type. A straight field-for-field
 *  copy on purpose: [EconomicsRow] is :control's own vocabulary and :control may not see :gateway,
 *  so the translation belongs here, in the composition root, and nowhere else. */
public class EconomicsStoreSource(private val store: EconomicsStore) : HeadEconomicsSource {
    override fun buckets(): List<EconomicsRow> = store.read().map {
        EconomicsRow(
            hour = it.hour,
            turns = it.turns,
            inTokens = it.inTokens,
            cachedTokens = it.cachedTokens,
            outTokens = it.outTokens,
            reqBytes = it.reqBytes,
            upstreamBytes = it.upstreamBytes,
            toolsEager = it.toolsEager,
            toolsDeferred = it.toolsDeferred,
            deferralTurns = it.deferralTurns,
            rateLimited = it.rateLimited,
        )
    }
}

public class PerfStatsSource(private val stats: PerfStats) :
    HeadPerfSource,
    HeadSessionPerfSource,
    HeadPerfSkipSource {
    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)

    /** V4-37: the same rows narrowed to one session — what the statusline's cost segment sums. */
    override fun tailNumericFor(sessionId: String): List<Map<String, Long>> =
        stats.tailNumericFor(sessionId)

    /** V4-45: how many rows that same reader had to DROP. Delegated live rather than snapshotted —
     *  the statusline renderer is cached per head and built once, so a captured number would freeze
     *  at whatever the count was when the head started (zero) and never say anything again. */
    override fun skippedRowCount(): Long = stats.skippedRowCount()
}
