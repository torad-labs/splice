// NEW: Oct 12, 2026 — a Compare models send is real spend on this head's account, so it leaves what a request leaves
// (marlin, Oct 12): a Requests row, an hourly-rollup entry and the day's spend, and it is weighed by the same budget a
// turn is. It is NOT a turn: no session, no trace, no client, so it takes the budget's admit() and writes its row
// here, not in the turn pipeline. The row carries PerfKeys.COMPARE, which keeps it out of "no project".
package splice.head.usage

import splice.core.budget.BudgetBlock
import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.core.util.Cancellables
import splice.core.util.FileIoTask
import splice.head.HeadDeps
import splice.head.perf.PerfAccount
import splice.head.perf.PerfRowMeta

/** What one Compare models send was and how it ended. [usage] is null where the stream said nothing about tokens. */
public data class CompareAnswer(
    val model: String,
    val account: String?,
    val outcome: String,
    val elapsedMs: Long,
    val usage: Usage?,
)

/** The head's side of a Compare models send: the budget's answer before the send, and the record after it. */
public interface CompareSends {
    /** Null admits the send; a [BudgetBlock] refuses it, and the refusal has left its row before this returns. */
    public fun admit(model: String, account: String?): BudgetBlock?

    /** The send went out and ended as [answer] says: its row, its rollup entry and its spend. A refusal by [admit]
     *  never reaches here, and leaves its row alone. */
    public fun answered(answer: CompareAnswer)
}

internal class HeadCompareSends(private val deps: HeadDeps) : CompareSends {
    private val clock get() = deps.seams.clock

    override fun admit(model: String, account: String?): BudgetBlock? {
        val block = deps.quotaBundle.budget.admit() ?: return null
        val perf = TurnPerf(clock = clock)
        perf.mark(PerfKeys.TOTAL)
        perf.setCount(PerfKeys.ATTEMPTS, 0)
        perf.setCount(PerfKeys.COMPARE, 1)
        TurnBill.counters(noRequestUsage).forEach { (key, value) -> perf.setCount(key, value) }
        write(model, account, OutcomeTag.BUDGET_BLOCKED.wire, perf.snapshot(), spend = false)
        return block
    }

    override fun answered(answer: CompareAnswer) {
        val perf = TurnPerf(clock = clock)
        perf.mark(PerfKeys.TOTAL)
        perf.setCount(PerfKeys.ATTEMPTS, 1)
        perf.setCount(PerfKeys.COMPARE, 1)
        answer.usage?.let { TurnBill.counters(it).forEach { (key, value) -> perf.setCount(key, value) } }
        write(answer.model, answer.account, answer.outcome, perf.snapshot(), spend = true)
    }

    /** A refused send leaves its row only: no rollup entry and no spend, as a refused turn leaves none, so the live hour
     *  agrees with the hour EconomicsFromRows rebuilds from the rows, which skips a refusal. */
    private fun write(model: String, account: String?, outcome: String, snap: PerfSnapshot, spend: Boolean) {
        val ts = deps.stores.perfStats.record(
            PerfRowMeta(model, outcome, compact = false, session = null, account = PerfAccount(account)),
            snap,
            whole = if (spend) FileIoTask { economics(snap, model, account, outcome) } else FileIoTask { },
        )
        if (spend) {
            Cancellables.discard(
                Cancellables.runCatchingCancellable {
                    deps.quotaBundle.budget.spentOn(account, ts, model, snap.counters, outcome)
                },
                "a Compare send's record is best-effort; it must never fail the send it describes",
            )
        }
    }

    private fun economics(snap: PerfSnapshot, model: String, account: String?, outcome: String) {
        val store = deps.stores.economicsStore ?: return
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                store.record(SnapshotEconomics().of(snap, model), account, outcome)
            },
            "a Compare send's record is best-effort; it must never fail the send it describes",
        )
    }
}
