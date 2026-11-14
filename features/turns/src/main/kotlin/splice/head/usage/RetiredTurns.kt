// NEW: #523. The next daemon's count of the turns a retiring daemon finished after it handed over. That process
// leaves each such turn's request row in the head's retired-rows folder (splice.head.perf.RetiredRows) and counts
// nothing itself, so the session's running total, the hour's rollup and the day's budget are each written by one
// process only. This class reads those rows into the same three places a turn of this daemon's own lands in.
package splice.head.usage

import splice.core.perf.PerfSnapshot
import splice.head.HeadEvents
import splice.head.perf.PerfStats
import splice.head.perf.RetiredRow

/** Counts what a retired head left, whenever this head records a row of its own: the folder is looked at then and
 *  at no other time, so a head that serves nothing counts a left row at its next turn. */
internal class RetiredTurns(
    private val perfStats: PerfStats,
    private val spend: TurnSpend,
    private val events: HeadEvents,
) {
    fun fold() {
        perfStats.retired.fold { row -> count(row) }
    }

    /** A row with no model is a request turned away before one was chosen: it cost nothing and belongs to no
     *  session's total, so only its ending is told. A turn that finished after its head retired was already
     *  answering, so it is never the one its provider refused at a limit. */
    private fun count(row: RetiredRow) {
        val model = row.model
        if (model != null) {
            row.session?.let { perfStats.totals?.add(it, model, row.counters, row.ts) }
            spend.retiredEconomics(row.ts, PerfSnapshot(emptyMap(), row.counters), model, row.account, row.outcome)
            spend.spend(row.ts, model, row.counters, row.account, row.outcome)
        }
        events.turnEnded(row.ts.toString(), row.outcome, row.sessionId)
    }
}
