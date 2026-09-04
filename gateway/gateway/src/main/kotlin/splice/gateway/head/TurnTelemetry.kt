// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnTelemetry, ERR_SNIPPET, and the cache log line +
// usageObj builder lifted out of finishTurn) @ 86f1411 — invariants unchanged: the per-turn
// observability surface — turn line, error lines, perf row, and now the cache log line too. Moving
// the cache line here (HD-24) is what lets TurnFinish drop its dependency on UsageHud: a log line
// is telemetry.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
import splice.spi.WatchdogFired

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
) {
    private val cache = TurnCacheLine(headKey)
    private val line = TurnLine(headKey)

    /** The sole perf-row emitter: total mark, one JSONL row, one log line. Never throws. */
    fun recordPerf(drive: TurnDrive, outcomeTag: String) {
        drive.perf.mark(PerfKeys.TOTAL)
        val snap = drive.perf.snapshot()
        val session = drive.sessionTag()
        perfStats.record(PerfRowMeta(drive.upstreamModel, outcomeTag, drive.meta.compact, session), snap)
        log(snap.perfLine(headKey, outcomeTag, drive.meta.compact, drive.upstreamModel, session))
    }

    fun errTurn(kind: String, drive: TurnDrive, detail: String): String =
        "[$headKey] turn ERROR $kind compact=${drive.meta.compact} latency=${clock() - drive.t0}ms $detail\n"

    fun turnLine(
        meta: TurnMeta,
        model: String,
        outcome: TurnOutcome,
        latencyMs: Long,
        fired: WatchdogFired? = null,
    ): String = line.render(meta, model, outcome, latencyMs, fired)

    /** MOVED out of finishTurn (HD-24): a log line is telemetry, and moving it here is what lets
     *  TurnFinish drop its dependency on UsageHud. [model] is the drive's upstream model; [headKey]
     *  is the same tag every other line in this class uses. */
    fun cacheLine(model: String, usage: Usage, compact: Boolean): String = cache.line(model, usage, compact)
}
