// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnTelemetry, ERR_SNIPPET, and the cache log line +
// usageObj builder lifted out of finishTurn) @ 86f1411 — invariants unchanged: the per-turn
// observability surface — turn line, error lines, perf row, and now the cache log line too. Moving
// the cache line here (HD-24) is what lets TurnFinish drop its dependency on UsageHud: a log line
// is telemetry.
package splice.head.turn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.budget.HeadBudget
import splice.core.budget.NoHeadBudget
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.PerfTurnIds
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageHistory
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.HeadEvents
import splice.head.NoHeadEvents
import splice.head.admission.LocalRefusal
import splice.head.perf.HeldRows
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnEconomics
import splice.head.wire.TurnTrace
import splice.upstream.retry.WatchdogFired
import splice.upstream.retry.WatchdogHeld

// V4-122: ERR_SNIPPET lives in splice.core.util now, at the same 200 this declaration carried.
// It was declared three times — here at 200, in UpstreamClient.kt and WsLogKeys.kt at 160 — for one
// meaning, so the same upstream message appeared at two lengths in one investigation. This value
// was the one KEPT because it is what FailureText.kt and TurnKnownEnd.kt use for text the client
// can SEE, and those bytes are oracle-pinned; the two log-only surfaces widened to match.

/** A posted round without a token report must not persist an invented zero in any billing bucket. */
private val UNREPORTED_TOKEN_FIELDS = setOf(
    PerfKeys.IN_TOKENS,
    PerfKeys.OUT_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.CACHE_WRITE_TOKENS,
    PerfKeys.ABSORBED_IN_TOKENS,
    PerfKeys.ABSORBED_OUT_TOKENS,
    PerfKeys.ABSORBED_CACHED_TOKENS,
    PerfKeys.ABSORBED_CACHE_WRITE_TOKENS,
)

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
    /** V4-133 review: the head's daily spend budget, told each turn's spend beside its perf row.
     *  Defaulted like [events]; the one production site (TurnDriver) passes the head's own, and
     *  HeadBudgetTest fails if a served turn stops reaching it. */
    private val budget: HeadBudget = NoHeadBudget,
) {
    private val cache = TurnCacheLine(headKey)
    private val line = TurnLine(headKey)

    /** Rows waiting on a source round, so a head stop can write them ([flushHeld]). */
    private val held = HeldRows()

    /** Drain the paced tail before the sole perf snapshot; publish even if cleanup throws cancellation.
     *  [rateLimited] marks the one turn the upstream refused with a 429 — see [recordEconomics]. */
    suspend fun recordPerf(
        drive: TurnDrive,
        outcomeTag: String,
        rateLimited: Boolean = false,
        cause: String? = null,
        layers: Int = 0,
        permanent: Boolean? = null,
    ) = withContext(NonCancellable) {
        permanent?.let { drive.perf.setCount(PerfKeys.FAILURE_PERMANENT, if (it) 1L else 0L) }
        // Cancellation still owes its row, even if a paced socket write itself throws cancellation.
        try {
            drive.channel.finishPacing(clock = clock)
        } finally {
            if (!drive.collectPerf.hold(this@TurnTelemetry, outcomeTag, rateLimited, cause, layers)) {
                recordSnapshot(drive, outcomeTag, rateLimited, cause, layers)
            }
        }
    }

    internal fun recordSnapshot(
        drive: TurnDrive,
        outcomeTag: String,
        rateLimited: Boolean,
        cause: String?,
        layers: Int,
    ) {
        drive.perf.mark(PerfKeys.TOTAL)
        drive.perf.setCount(PerfKeys.ATTEMPTS, drive.perfCounter(PerfKeys.ATTEMPTS))
        val ending = RowEnding(outcomeTag, rateLimited, cause, layers, perfStats.clock())
        held.write(drive.sourceRow) { usage -> writeRow(drive, ending, usage) }
    }

    /** Every row still waiting on a source round, written with what is known: a head stop calls this once its
     *  provider has stopped those rounds, so no row outlives the stop. A hard kill loses them. */
    fun flushHeld() = held.flush()

    /** The turn's ending as it stood when its row was asked for; a held row is written with it later. */
    private data class RowEnding(
        val outcomeTag: String,
        val rateLimited: Boolean,
        val cause: String?,
        val layers: Int,
        val at: Long,
    )

    /** [usage] is the turn's whole usage once a source round it held settled; its counters replace the turn's. */
    private fun writeRow(drive: TurnDrive, ending: RowEnding, usage: Usage?) {
        val outcomeTag = ending.outcomeTag
        // A held row may carry an interceptor's assembled step, not the raw rounds this drive posted.
        val billed = drive.rawRoundUsage() ?: usage
        billed?.let { TurnBill.counters(it).forEach { (key, value) -> drive.perf.setCount(key, value) } }
        val snap = billingSnapshot(drive)
        // V4-174: the turn record closes on the same snapshot the perf row carries — every ending
        // of a drive goes through here, so the trace never has a turn without its outcome.
        closeTrace(drive.trace, outcomeTag, snap)
        val session = drive.sessionTag()
        val account = drive.account
        val rowTs = perfStats.record(
            PerfRowMeta(
                drive.upstreamModel,
                outcomeTag,
                drive.meta.compact,
                session,
                drive.observedAccountLabel ?: account?.account?.label ?: drive.fallbackAccountLabel,
                account?.cacheCold == true,
                // V4-117: the cause and the loop's own attempt count ride the row beside the tag. The
                // outcome TAG is not replaced — it is what the operator already greps — so this is an
                // addition to the row, never a change to the string that identifies it.
                cause = ending.cause,
                layers = ending.layers,
                // V4-345: the trace turn that recorded the request and answer; absent when capture is off.
                turns = PerfTurnIds(trace = drive.trace?.turnId, request = drive.turnId),
                sessionId = drive.meta.sessionId,
                responseMessageId = drive.emitter.responseMessageId,
                conversationKey = drive.meta.conversationKey,
            ),
            snap,
            drive.requestBody,
            ending.at,
        )
        account?.switch?.let { switched ->
            log("[$headKey] account ${switched.from} -> ${switched.to}: ${switched.reason}\n")
            events.accountSwitched(switched.from, switched.to)
        }
        // V4-134: the turn's end goes to the console only once its row exists, carrying that row's
        // key, so the stream never names a row /api/perf/turns has not been handed.
        events.turnEnded(rowTs.toString(), outcomeTag, drive.meta.sessionId)
        log(snap.perfLine(headKey, outcomeTag, drive.meta.compact, drive.upstreamModel, session))
        recordEconomics(snap, drive.upstreamModel, ending.rateLimited)
        recordSpend(rowTs, drive.upstreamModel, snap.counters)
    }

    private fun billingSnapshot(drive: TurnDrive): PerfSnapshot {
        val snap = drive.perf.snapshot()
        val posted = drive.roundInterceptor != null &&
            (snap.counters[PerfKeys.UPSTREAM_REQ_BYTES] ?: 0L) > 0L
        if (!posted) return snap
        val counters = snap.counters - PerfKeys.LOCAL_STEP
        val observed = drive.rawRoundUsage()?.reported.orEmpty().isNotEmpty()
        return if (TurnBill.isEmpty(counters) && !observed) {
            snap.copy(counters = counters.filterKeys { it !in UNREPORTED_TOKEN_FIELDS })
        } else {
            snap.copy(counters = counters)
        }
    }

    /** V4-404: the one place a turn's trace is closed, from both endings (a drive's perf row and a local
     *  refusal). The outcome's sentence is recorded beside its tag unless the ending's own surface already
     *  spoke, so no failed turn's record closes without words (only TurnConnEnd used to speak). */
    private fun closeTrace(trace: TurnTrace?, outcomeTag: String, snap: PerfSnapshot) {
        if (trace == null) return
        OutcomeSentences.of(outcomeTag)?.let(trace::failureSentenceUnlessSpoken)
        trace.finish(outcomeTag, snap)
    }

    /** V4-133 review: the day's spend moves by THIS row — its own ts, model and counters, the same
     *  facts a restarted daemon reads back from the file. Best-effort like [recordEconomics]: a
     *  budget that throws must never fail the turn it is weighing. A local refusal writes no spend,
     *  because it spent nothing. */
    private fun recordSpend(rowTs: Long, model: String, counters: Map<String, Long>) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { budget.spent(rowTs, model, counters) },
            "telemetry is best-effort; a turn must never fail on its budget",
        )
    }

    /** Fold this turn into the hourly quota rollup. The perf snapshot is the single source for
     *  BOTH the JSONL row and this store, so the dashboard can never disagree with the log line.
     *  Wrapped because recordPerf's contract is never-throws and telemetry must not fail a turn. */
    private fun recordEconomics(snap: PerfSnapshot, model: String, rateLimited: Boolean) {
        val store = economics ?: return
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                store.record(
                    TurnEconomics(
                        // V4-221: priced at THIS turn's card, the model the perf row names.
                        model = model,
                        localStep = snap.counters[PerfKeys.LOCAL_STEP] == 1L,
                        inTokens = snap.counters[PerfKeys.IN_TOKENS],
                        cachedTokens = snap.counters[PerfKeys.CACHED_TOKENS],
                        cacheWriteTokens = snap.counters[PerfKeys.CACHE_WRITE_TOKENS],
                        outTokens = snap.counters[PerfKeys.OUT_TOKENS],
                        reqBytes = snap.counters[PerfKeys.REQ_BYTES],
                        upstreamBytes = snap.counters[PerfKeys.UPSTREAM_REQ_BYTES],
                        // Absent (not zero) on a head whose dialect cannot defer — the chat dialect
                        // has no tool_search at all, and the ledger must render that as "n/a",
                        // never as a deferral rate of zero.
                        toolsEager = snap.counters[PerfKeys.TOOLS_EAGER],
                        toolsDeferred = snap.counters[PerfKeys.TOOLS_DEFERRED],
                        rateLimited = rateLimited,
                        history = UsageHistory(
                            absorbed = TurnBill.absorbed(snap.counters),
                            cutRounds = snap.counters[PerfKeys.CUT_SOURCE_ROUNDS] ?: 0L,
                        ),
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
        perf.setCount(PerfKeys.ATTEMPTS, 0)
        val snap = perf.snapshot()
        closeTrace(trace, tag, snap)
        val rowMeta = PerfRowMeta(
            meta.upstreamModel,
            tag,
            meta.compact,
            session,
            account = refusal.account,
            turns = PerfTurnIds(trace = trace?.turnId),
            sessionId = meta.sessionId,
        )
        val rowTs = perfStats.record(rowMeta, snap)
        // V4-134: a local refusal is a turn that ended too — it has a perf row, so it has a turn.end.
        events.turnEnded(rowTs.toString(), tag, meta.sessionId)
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
