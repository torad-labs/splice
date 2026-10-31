// Split out of splice/head/turn/TurnTelemetry.kt (2026-10-13, the concentration ratchet): where a
// turn's spend lands once its perf row exists. Both sinks are best-effort and were TurnTelemetry's
// only reason to know about the quota rollup and the budget; the bodies are unchanged.
package splice.head.usage

import splice.core.budget.HeadBudget
import splice.core.budget.NoHeadBudget
import splice.core.perf.PerfSnapshot
import splice.core.util.Cancellables

/** The two places a served turn's spend is told: the hourly quota rollup and the head's daily budget. */
internal class TurnSpend(
    /** The hourly quota rollup, or null on a head assembled without one — then a turn records no
     *  economics, which is the honest reading, never a zero-burn row. */
    private val economics: EconomicsStore? = null,
    /** V4-133 review: the head's daily spend budget, told each turn's spend beside its perf row.
     *  Defaulted because tests build TurnTelemetry directly; the one production site (TurnDriver)
     *  passes the head's own, and HeadBudgetTest fails if a served turn stops reaching it. */
    private val budget: HeadBudget = NoHeadBudget,
) {
    /** V4-133 review: the day's spend moves by THIS row — its own ts, model and counters, the same
     *  facts a restarted daemon reads back from the file. Best-effort like [economics]: a
     *  budget that throws must never fail the turn it is weighing. A local refusal writes no spend,
     *  because it spent nothing. */
    fun spend(
        rowTs: Long,
        model: String,
        counters: Map<String, Long>,
        account: String?,
        outcome: String,
    ) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { budget.spentOn(account, rowTs, model, counters, outcome) },
            "telemetry is best-effort; a turn must never fail on its budget",
        )
    }

    /** Fold this turn into the hourly quota rollup. The perf snapshot is the single source for
     *  BOTH the JSONL row and this store, so the dashboard can never disagree with the log line.
     *  Wrapped because recordPerf's contract is never-throws and telemetry must not fail a turn. */
    fun economics(
        snap: PerfSnapshot,
        model: String,
        rateLimited: Boolean,
        account: String?,
        outcome: String,
    ) {
        val store = economics ?: return
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                store.record(SnapshotEconomics().of(snap, model, rateLimited), account, outcome)
            },
            "telemetry is best-effort; a turn must never fail on the quota rollup",
        )
    }
}
