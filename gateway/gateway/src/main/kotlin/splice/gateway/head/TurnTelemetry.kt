// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnTelemetry, ERR_SNIPPET, and the cache log line +
// usageObj builder lifted out of finishTurn) @ 86f1411 — invariants unchanged: the per-turn
// observability surface — turn line, error lines, perf row, and now the cache log line too. Moving
// the cache line here (HD-24) is what lets TurnFinish drop its dependency on UsageHud: a log line
// is telemetry.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.TurnEconomics
import splice.spi.AccountResetText
import splice.spi.WatchdogFired
import splice.spi.WatchdogHeld

// MERGED: TurnDriver's and TurnTelemetry's private companions each carried an identical
// `ERR_SNIPPET = 200`. Two file-scope consts cannot share a name, and the two values were never
// meant to diverge — one declaration now serves both. WIDENED to `internal` (was `private` on
// TurnDriver.kt): TurnFailures.kt, TurnEnding.kt and TearAwareEvents.kt all read it now that the
// error-surfacing code that used to share TurnDriver.kt's file scope lives across four files.
internal const val ERR_SNIPPET = 200

/** Renders the per-turn observability: the turn line, error lines, the perf row+line, and the
 *  cache log line. Split out so the driver stays drive-only (the audit's god-file finding). */
internal class TurnTelemetry(
    private val headKey: String,
    private val perfStats: PerfStats,
    private val log: LogSink,
    private val clock: ElapsedClock,
    /** The hourly quota rollup, or null on a head assembled without one — then a turn records no
     *  economics, which is the honest reading, never a zero-burn row. */
    private val economics: EconomicsStore? = null,
) {
    private val cache = TurnCacheLine(headKey)
    private val line = TurnLine(headKey)

    /** The sole perf-row emitter: total mark, one JSONL row, one log line. Never throws.
     *  [rateLimited] marks the one turn the upstream refused with a 429 — see [recordEconomics]. */
    fun recordPerf(drive: TurnDrive, outcomeTag: String, rateLimited: Boolean = false) {
        drive.perf.mark(PerfKeys.TOTAL)
        val snap = drive.perf.snapshot()
        val session = drive.sessionTag()
        val account = drive.account
        perfStats.record(
            PerfRowMeta(
                drive.upstreamModel,
                outcomeTag,
                drive.meta.compact,
                session,
                account?.account?.label,
                account?.cacheCold == true,
            ),
            snap,
        )
        account?.switch?.let { switched ->
            log("[$headKey] account ${switched.from} -> ${switched.to}: ${switched.reason}\n")
        }
        log(snap.perfLine(headKey, outcomeTag, drive.meta.compact, drive.upstreamModel, session))
        recordEconomics(snap, rateLimited)
    }

    /** Fold this turn into the hourly quota rollup. The perf snapshot is the single source for
     *  BOTH the JSONL row and this store, so the dashboard can never disagree with the log line.
     *  Wrapped because recordPerf's contract is never-throws and telemetry must not fail a turn. */
    private fun recordEconomics(snap: PerfSnapshot, rateLimited: Boolean) {
        val store = economics ?: return
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                store.record(
                    TurnEconomics(
                        inTokens = snap.counters[PerfKeys.IN_TOKENS] ?: 0,
                        cachedTokens = snap.counters[PerfKeys.CACHED_TOKENS] ?: 0,
                        // V4-86: the cache-WRITE bucket. Absent on a perf row written before the
                        // counter existed, which reads as 0 — the true historical value, since
                        // nothing was counting it. Without this line the counter TurnUsageStamp
                        // writes on every turn died right here and the rollup saw a cache write
                        // as ordinary input.
                        cacheWriteTokens = snap.counters[PerfKeys.CACHE_WRITE_TOKENS] ?: 0,
                        outTokens = snap.counters[PerfKeys.OUT_TOKENS] ?: 0,
                        reqBytes = snap.counters[PerfKeys.REQ_BYTES],
                        upstreamBytes = snap.counters[PerfKeys.UPSTREAM_REQ_BYTES],
                        // Absent (not zero) on a head whose dialect cannot defer — the chat dialect
                        // has no tool_search at all, and the ledger must render that as "n/a",
                        // never as a deferral rate of zero.
                        toolsEager = snap.counters[PerfKeys.TOOLS_EAGER],
                        toolsDeferred = snap.counters[PerfKeys.TOOLS_DEFERRED],
                        rateLimited = rateLimited,
                    ),
                )
            },
            "telemetry is best-effort; a turn must never fail on the quota rollup",
        )
    }

    /** Records a pool refusal that happens after parsing but before a [TurnDrive] can exist. */
    /** V4-55: the admission-side rate-limit refusal (HeadAdmission.refuseIfRateLimited), mirroring
     *  [recordAccountExhausted] because it is the same KIND of event — a turn refused locally,
     *  before any upstream call — and the two must be equally visible. Review of V4-50 found the
     *  refusal wrote NOTHING: no perf row, no journal line. That is the precise blindness that made
     *  three operator reports in one day unfalsifiable, sitting on the path built to answer them.
     *
     *  BOTH horizons are logged because they are different facts and only one is the operator's:
     *  provider_reset is when the quota actually returns, gateway_hold is how long splice is holding
     *  its own retries. A line carrying only the second reads as "back in two minutes" against an
     *  88-minute reset. */
    fun recordRateLimited(
        meta: TurnMeta,
        perf: TurnPerf,
        t0: Long,
        resetEpochSeconds: Long?,
        armedMs: Long,
    ) {
        val outcome = "error:rate-limited"
        val reset = AccountResetText.format(resetEpochSeconds)
        val session = meta.sessionId?.take(SESSION_TAG_CHARS)
        perf.mark(PerfKeys.TOTAL)
        val snap = perf.snapshot()
        perfStats.record(PerfRowMeta(meta.upstreamModel, outcome, meta.compact, session), snap)
        log(
            "[$headKey] turn ERROR rate-limited compact=${meta.compact} " +
                "latency=${clock() - t0}ms provider_reset=$reset gateway_hold=${armedMs}ms\n",
        )
        log(snap.perfLine(headKey, outcome, meta.compact, meta.upstreamModel, session))
    }

    fun recordAccountExhausted(
        meta: TurnMeta,
        perf: TurnPerf,
        t0: Long,
        earliestResetEpochSeconds: Long?,
    ) {
        val outcome = "error:all-accounts-exhausted"
        val reset = AccountResetText.format(earliestResetEpochSeconds)
        val session = meta.sessionId?.take(SESSION_TAG_CHARS)
        perf.mark(PerfKeys.TOTAL)
        val snap = perf.snapshot()
        perfStats.record(PerfRowMeta(meta.upstreamModel, outcome, meta.compact, session), snap)
        log(
            "[$headKey] turn ERROR all-accounts-exhausted compact=${meta.compact} " +
                "latency=${clock() - t0}ms earliest_reset=$reset\n",
        )
        log(snap.perfLine(headKey, outcome, meta.compact, meta.upstreamModel, session))
    }

    fun errTurn(kind: String, drive: TurnDrive, detail: String): String =
        "[$headKey] turn ERROR $kind compact=${drive.meta.compact} latency=${clock() - drive.t0}ms $detail\n"

    fun turnLine(
        meta: TurnMeta,
        model: String,
        outcome: TurnOutcome,
        latencyMs: Long,
        fired: WatchdogFired? = null,
        held: WatchdogHeld? = null,
    ): String = line.render(meta, model, outcome, latencyMs, fired, held)

    /** MOVED out of finishTurn (HD-24): a log line is telemetry, and moving it here is what lets
     *  TurnFinish drop its dependency on UsageHud. [model] is the drive's upstream model; [headKey]
     *  is the same tag every other line in this class uses. */
    fun cacheLine(model: String, usage: Usage, compact: Boolean): String = cache.line(model, usage, compact)
}
