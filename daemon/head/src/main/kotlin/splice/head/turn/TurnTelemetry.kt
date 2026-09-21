// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnTelemetry, ERR_SNIPPET, and the cache log line +
// usageObj builder lifted out of finishTurn) @ 86f1411 — invariants unchanged: the per-turn
// observability surface — turn line, error lines, perf row, and now the cache log line too. Moving
// the cache line here (HD-24) is what lets TurnFinish drop its dependency on UsageHud: a log line
// is telemetry.
package splice.head.turn

import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.HeadEvents
import splice.head.NoHeadEvents
import splice.head.admission.LocalRefusal
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnEconomics
import splice.upstream.retry.WatchdogFired
import splice.upstream.retry.WatchdogHeld

// V4-122: ERR_SNIPPET lives in splice.core.util now, at the same 200 this declaration carried.
// It was declared three times — here at 200, in UpstreamClient.kt and WsLogKeys.kt at 160 — for one
// meaning, so the same upstream message appeared at two lengths in one investigation. This value
// was the one KEPT because it is what FailureText.kt and TurnKnownEnd.kt use for text the client
// can SEE, and those bytes are oracle-pinned; the two log-only surfaces widened to match.

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
    /** V4-134: the console's view of this head. Defaulted like [economics] because tests build this
     *  class directly; the one production site (TurnDriver) passes the head's own, and HeadEventsTest
     *  fails if a served turn stops reaching it. */
    private val events: HeadEvents = NoHeadEvents,
) {
    private val cache = TurnCacheLine(headKey)
    private val line = TurnLine(headKey)

    /** The sole perf-row emitter: total mark, one JSONL row, one log line. Never throws.
     *  [rateLimited] marks the one turn the upstream refused with a 429 — see [recordEconomics]. */
    fun recordPerf(
        drive: TurnDrive,
        outcomeTag: String,
        rateLimited: Boolean = false,
        cause: String? = null,
        layers: Int = 0,
    ) {
        drive.perf.mark(PerfKeys.TOTAL)
        val snap = drive.perf.snapshot()
        // V4-174: the turn record closes on the same snapshot the perf row carries — every ending
        // of a drive goes through here, so the trace never has a turn without its outcome.
        drive.trace?.finish(outcomeTag, snap)
        val session = drive.sessionTag()
        val account = drive.account
        val rowTs = perfStats.record(
            PerfRowMeta(
                drive.upstreamModel,
                outcomeTag,
                drive.meta.compact,
                session,
                account?.account?.label,
                account?.cacheCold == true,
                // V4-117: the cause and the loop's own attempt count ride the row beside the tag. The
                // outcome TAG is not replaced — it is what the operator already greps — so this is an
                // addition to the row, never a change to the string that identifies it.
                cause = cause,
                layers = layers,
            ),
            snap,
        )
        account?.switch?.let { switched ->
            log("[$headKey] account ${switched.from} -> ${switched.to}: ${switched.reason}\n")
            events.accountSwitched(switched.from, switched.to)
        }
        // V4-134: the turn's end goes to the console only once its row exists, carrying that row's
        // key, so the stream never names a row /api/perf/turns has not been handed.
        events.turnEnded(rowTs.toString(), outcomeTag)
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

    /** V4-99 item 4: ONE entry for every turn refused LOCALLY — after parsing, before any upstream
     *  call — whether the reason was the admission rate limit or an exhausted account pool. The two
     *  were separate functions with identical bodies differing only in a tag and a detail string,
     *  which is the shape that drifts: a third local refusal would have been a third copy.
     *
     *  THE VISIBILITY IS THE POINT (V4-55): a refusal that leaves no perf row and no journal line is,
     *  from splice's own telemetry, a turn that never happened — which is how three operator reports
     *  of this exact failure went unfalsifiable in one day, sitting on the path built to answer them.
     *
     *  [tag] is the outcome tag (it names the refusal in the perf row AND supplies the journal's
     *  word); [detail] carries the facts that differ between refusals — both horizons for the rate
     *  limit, because provider_reset is when the quota returns and gateway_hold is how long splice is
     *  holding its own retries, and a line carrying only the second reads as "back in two minutes"
     *  against an 88-minute reset. */
    fun recordLocalRefusal(meta: TurnMeta, perf: TurnPerf, t0: Long, refusal: LocalRefusal) {
        val (tag, detail, trace) = refusal
        val session = meta.sessionId?.take(SESSION_TAG_CHARS)
        perf.mark(PerfKeys.TOTAL)
        val snap = perf.snapshot()
        trace?.finish(tag, snap)
        val rowTs = perfStats.record(PerfRowMeta(meta.upstreamModel, tag, meta.compact, session), snap)
        // V4-134: a local refusal is a turn that ended too — it has a perf row, so it has a turn.end.
        events.turnEnded(rowTs.toString(), tag)
        // The tag is printed VERBATIM, the same spelling the perf row on the next line carries
        // (kt-outcome-tag-single-source, V4-99). It used to be `substringAfter("error:")`, which
        // meant this line and the perf row spelled one field two ways — and that the rendering
        // depended on the FAMILY: an `error:` tag lost its prefix while a `failure:` tag kept it.
        // A stripped prefix is not a named value anywhere in core (OutcomeTag keeps its prefixes
        // private and exposes only the two builders), so re-deriving it here would have been a
        // second spelling of the same contract — the exact defect the rule exists to prevent.
        log(
            "[$headKey] turn ERROR $tag compact=${meta.compact} " +
                "latency=${clock() - t0}ms $detail\n",
        )
        log(snap.perfLine(headKey, tag, meta.compact, meta.upstreamModel, session))
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
