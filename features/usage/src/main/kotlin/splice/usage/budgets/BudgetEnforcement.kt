// NEW: V4-133 review — the budgets GET/PUT /api/budgets stores, ENFORCED per head and per day.
//
// THE PROMISE, and all of it: "`warn` tells the operator, `block` refuses the turn"
// (console/src/entities/budget/model/types.ts). Before this file a PUT with `block` answered 200 and
// every turn on that head kept being served, because nothing on the turn path read a budget. Each head
// is handed ONE ledger ([BudgetEnforcement.forHead], through HeadServerFactory): admit() before a
// turn, spent() after its perf row. `block` refuses the turn once today's spend has reached the limit;
// `warn` tells the operator once, through the saved alert webhook and the head's log, and serves it.
//
// TODAY IS THE OPERATOR'S DAY, midnight to midnight where the daemon runs (Marlin, Oct 10, 2026). On
// the UTC day a Chicago budget lifted at 7 PM. Accounts draws the same boundary in the browser, and
// /api/projects' cost_today_usd still draws the UTC day. A new day starts from nothing. A turn on a
// model with NO rate card has no price: it is not counted, and never silently — the head's log names
// the model once a day, and a refusal says how many it could not count.
//
// THE FILES: this one holds the two ports and the entry point; BudgetLedger.kt one head's ledger,
// BudgetDay.kt its day, core's TurnPrice the price of a turn and BudgetText.kt every sentence it says.
package splice.usage.budgets

import splice.core.budget.HeadBudget
import splice.core.model.ModelCatalog
import splice.core.model.TurnPrice
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Where one head's perf rows are read back from: the files its PerfStats writes and archives. */
public fun interface HeadPerfHistory {
    public fun rowsFor(head: String): PerfRowsSource
}

/** How a reached `warn` budget tells the operator. Must return at once: it runs on the turn path. */
public fun interface BudgetAlert {
    public fun budgetReached(head: String, text: String)
}

public class BudgetEnforcement(
    budgets: BudgetStore,
    alert: BudgetAlert,
    private val history: HeadPerfHistory,
    log: LogSink,
    clock: WallClock = WallClock(System::currentTimeMillis),
    seed: BudgetSeedRuntime,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val context =
        LedgerContext(BudgetPolicy(budgets, seed), alert, log, clock, zone, bootMs = clock(), seed = seed)
    private val ledgers = ConcurrentHashMap<String, BudgetLedger>()

    /** [head]'s ledger, pricing its turns against [catalog]'s rate cards. One per head: the key is
     *  bound here, so a head can never spend against another's budget. */
    public fun forHead(head: String, catalog: ModelCatalog?): HeadBudget =
        ledgers.computeIfAbsent(head) { BudgetLedger(head, TurnPrice(catalog), history.rowsFor(head), context) }

    /** When the budget day next rolls over, as the LEDGER draws it: admission blocks until this instant, so a
     *  console on another clock draws the day splice enforces rather than its own browser's midnight
     *  (re-review, Oct 10). */
    public fun dayResetsAtMs(): Long = context.dayStart(context.localDay(context.clock()) + 1)

    /** The instant the budget day now running began, in the daemon's zone: the calendar day's own midnight, never
     *  tomorrow's minus 24 hours, which is wrong on the two days a year the clock changes (a 25-hour November day,
     *  a 23-hour March one). */
    public fun dayStartedAtMs(): Long = context.dayStart(context.localDay(context.clock()))

    /** Unknown when no live head ledger exists; otherwise the same daily tally admission weighs. */
    internal fun spending(head: String): BudgetSpend? = ledgers[head]?.snapshot()
}

/** Exact head-wide priced spend is unknown if history is unreadable or any turn has no rate card. */
internal data class BudgetSpend(
    val usedUsd: Double?,
    val remainingUsd: Double?,
    val unpricedTurns: Long,
    val complete: Boolean,
    /** True only while the daemon is still reading this day's pre-boot history. */
    val pending: Boolean = false,
)
