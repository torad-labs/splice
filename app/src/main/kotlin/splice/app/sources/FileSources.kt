// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app.sources

import kotlinx.serialization.json.JsonPrimitive
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.core.util.JsonScalars
import splice.head.compact.CompactStats
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.usage.economics.EconomicsRow
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.HeadPerfSkipSource
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.HeadSessionPerfSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView

public class UsageStoreSource(
    private val store: UsageStore,
    private val quota: QuotaTracker? = null,
) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens, it.observedAtEpochSeconds)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit, quota?.snapshot()?.let(::quotaView))
    }

    private fun quotaView(snapshot: QuotaSnapshot): QuotaView {
        val observed = snapshot.observedAtEpochSeconds
        return QuotaView(
            fiveHour = snapshot.fiveHour?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt, observed) },
            sevenDay = snapshot.sevenDay?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt, observed) },
            plan = snapshot.plan,
        )
    }
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        // A primitive's CONTENT, not its JSON text: `toString()` on a JsonPrimitive string keeps its
        // quotes, so every tail row carried `"model_text"` (quotes included) while `byOutcome`, read
        // through `.content` in CompactStats, carried `model_text` - and the console, matching the
        // bare names, printed every event with its quotes and a `warn` edge (measured on the live
        // /api/compact 2026-09-23: 50 of 50 tail rows). A JSON null is an absent field, not the
        // word "null"; an object or array value keeps its JSON text.
        val tail = s.tail.map { row ->
            row.entries.mapNotNull { (key, v) ->
                val text = if (v is JsonPrimitive) JsonScalars.str(v) else v.toString()
                text?.let { key to it }
            }.toMap()
        }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

/** The hourly quota rollup, projected onto the control plane's row type. A straight field-for-field
 *  copy on purpose: [EconomicsRow] is :daemon-control's own vocabulary and :daemon-control may not see :daemon-head,
 *  so the translation belongs here, in the composition root, and nowhere else. */
public class EconomicsStoreSource(private val store: EconomicsStore) : HeadEconomicsSource {
    override fun buckets(): List<EconomicsRow> = store.read().map {
        EconomicsRow(
            hour = it.hour,
            turns = it.turns,
            inTokens = it.inTokens,
            cachedTokens = it.cachedTokens,
            cacheWriteTokens = it.cacheWriteTokens,
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
