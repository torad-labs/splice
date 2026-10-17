// NEW: V4-444 — the rows splice writes for a request it answered or turned away itself, split from TurnTelemetry
// (concentration, 2026-10-10) so that file is not billed for rows no model turn stands behind.
package splice.head.perf

import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.noRequestUsage
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.turn.SESSION_TAG_CHARS

internal class LocalAnswerRows(
    private val headKey: String,
    private val perfStats: PerfStats,
    private val log: LogSink,
    private val clock: ElapsedClock,
) {
    /** A request splice turned away at the gate (V4-444), before its body was read, so it has no model and no
     *  compaction flag. It leaves a row so the Requests list shows what splice itself declined, and it fires neither
     *  turn.start nor turn.end: no turn began, so announcing an end would leave the console a close with no open. */
    fun gateRefusal(tag: OutcomeTag, session: String?) {
        val perf = TurnPerf(clock = clock)
        perf.mark(PerfKeys.TOTAL)
        perf.setCount(PerfKeys.ATTEMPTS, 0)
        TurnBill.counters(noRequestUsage).forEach { (key, value) -> perf.setCount(key, value) }
        val snap = perf.snapshot()
        perfStats.record(
            PerfRowMeta(
                model = null,
                outcome = tag.wire,
                compact = false,
                session = session?.take(SESSION_TAG_CHARS),
                transcript = PerfTranscriptIds(sessionId = session),
            ),
            snap,
        )
        log("[$headKey] request refused at the gate: ${tag.wire}\n")
    }

    /** Claude Code's activity side query is answered by the head with no model. It leaves a row of its own,
     *  marked as a local step so no turn figure counts it, and as an activity query so a view can name its kind. */
    fun activityAnswer(sessionId: String?, wireModel: String, perf: TurnPerf) {
        perf.mark(PerfKeys.TOTAL)
        perf.setCount(PerfKeys.LOCAL_STEP, 1)
        perf.setCount(PerfKeys.ACTIVITY_QUERY, 1)
        perfStats.record(
            PerfRowMeta(
                wireModel,
                OutcomeTag.OK.wire,
                compact = false,
                session = sessionId?.take(SESSION_TAG_CHARS),
                transcript = PerfTranscriptIds(sessionId = sessionId),
            ),
            perf.snapshot(),
        )
    }
}
