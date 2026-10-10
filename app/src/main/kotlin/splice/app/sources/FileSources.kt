// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import splice.core.model.TurnPrice
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTotal
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindow
import splice.core.usage.QuotaWindowView
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.head.compact.CompactStats
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.perf.PerfStats
import splice.head.perf.SessionTotals
import splice.head.usage.EconomicsBucket
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.usage.economics.EconomicsBytes
import splice.usage.economics.EconomicsCost
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.EconomicsTokens
import splice.usage.economics.EconomicsTools
import splice.usage.economics.EconomicsTurnCounts
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.HeadPerfSkipSource
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.HeadSessionPerfSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.QuotaPoller
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.util.concurrent.TimeUnit

public class UsageStoreSource(
    private val store: UsageStore,
    private val quota: QuotaTracker? = null,
    private val pollers: List<QuotaPoller> = emptyList(),
) : HeadUsageSource {
    override suspend fun probeNow() {
        coroutineScope { pollers.map { poller -> async { poller.probeNow() } }.awaitAll() }
    }

    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens, it.observedAtEpochSeconds)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit, quota?.snapshot()?.let(::quotaView))
    }

    private fun windowView(window: QuotaWindow, observed: Long?): QuotaWindowView =
        QuotaWindowView(window.usedPercent.toInt(), window.resetsAt, observed, window.windowSeconds)

    private fun quotaView(snapshot: QuotaSnapshot): QuotaView {
        val observed = snapshot.observedAtEpochSeconds
        return QuotaView(
            fiveHour = snapshot.fiveHour?.let { windowView(it, observed) },
            sevenDay = snapshot.sevenDay?.let { windowView(it, observed) },
            plan = snapshot.plan,
            noUsageAt = snapshot.takeIf { it.answeredEmpty }?.let { TimeUnit.MILLISECONDS.toSeconds(it.updatedAt) },
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
        return CompactView(s.total, s.byOutcome, tail, s.span)
    }
}

/** The hourly quota rollup, projected onto the control plane's row type. A straight field-for-field
 *  copy on purpose: [EconomicsRow] is :daemon-control's own vocabulary and :daemon-control may not see :daemon-head,
 *  so the translation belongs here, in the composition root, and nowhere else. */
public class EconomicsStoreSource(
    private val store: EconomicsStore,
    perfRows: PerfRowsFileSource? = null,
    /** The cards splice holds NOW, which price again the hours whose turns had none when they ran. */
    price: TurnPrice? = null,
    /** How far back this install keeps its history: the edge the backfill may reach back to. */
    window: HistoryWindow = HistoryWindow(HISTORY_DEFAULT_DAYS),
    clock: WallClock = WallClock(System::currentTimeMillis),
) : HeadEconomicsSource {
    private val probes = perfRows?.let(::ProbeEconomics)
    private val reprice = perfRows?.let { rows -> price?.let { EconomicsReprice(rows, it) } }
    private val backfill = perfRows?.let { rows ->
        price?.let { EconomicsBackfill(rows, it, window, clock) }
    }

    override fun read(): EconomicsRead {
        val held = store.read()
        // The hours splice holds the turns for and the rollup has none for are written into the
        // rollup first, so every reader below sees one set of hours and the scan happens once.
        val whole = backfill?.missing(held)?.takeIf { it.isNotEmpty() }?.let { store.backfill(it) } ?: held
        val kept = when (val deduction = probes?.withoutProbes(whole) ?: ProbeDeduction.Done(whole)) {
            is ProbeDeduction.Unavailable -> return EconomicsRead.Unavailable(deduction.gap.sentence)
            is ProbeDeduction.Done -> deduction.buckets
        }
        val current = reprice?.priced(kept) ?: kept
        return EconomicsRead.Rows(current.map { row(it) })
    }

    private fun row(it: EconomicsBucket): EconomicsRow =
        EconomicsRow(
            hour = it.hour,
            counts = EconomicsTurnCounts(it.turns, it.localSteps, it.counts.unreportedUsageTurns),
            tokens = EconomicsTokens(
                inTokens = it.inTokens,
                cachedTokens = it.cachedTokens,
                cacheWriteTokens = it.cacheWriteTokens,
                outTokens = it.outTokens,
            ),
            bytes = EconomicsBytes(reqBytes = it.reqBytes, upstreamBytes = it.upstreamBytes),
            tools = EconomicsTools(
                toolsEager = it.toolsEager,
                toolsDeferred = it.toolsDeferred,
                deferralTurns = it.deferralTurns,
            ),
            rateLimited = it.rateLimited,
            cost = EconomicsCost(costUsd = it.costUsd, unpricedTurns = it.unpricedTurns),
        )
}

public class PerfStatsSource(private val stats: PerfStats) :
    HeadPerfSource,
    HeadSessionPerfSource,
    HeadPerfSkipSource {
    /** The same live totals instance the head's PerfStats feeds, never a second file reader. */
    internal val sessionTotals: SessionTotals? get() = stats.totals

    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)

    /** V4-37: the same rows narrowed to one session — what the statusline's cost segment sums. */
    override fun sessionTail(sessionId: String): PerfSessionTail = stats.sessionTail(sessionId)

    /** V4-244: that session's running total, kept as its rows were appended. */
    override fun sessionTotal(sessionId: String): PerfSessionTotal? = stats.totals?.totalFor(sessionId)

    /** V4-45: how many rows that same reader had to DROP. Delegated live rather than snapshotted —
     *  the statusline renderer is cached per head and built once, so a captured number would freeze
     *  at whatever the count was when the head started (zero) and never say anything again. */
    override fun skippedRowCount(): Long = stats.skippedRowCount()
}
