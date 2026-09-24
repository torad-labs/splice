// NEW: V4-133 review — the budgets GET/PUT /api/budgets stores, ENFORCED per head and per UTC day.
//
// THE PROMISE, and all of it: "`warn` tells the operator, `block` refuses the turn"
// (console/src/entities/budget/model/types.ts). Before this file a PUT with `block` answered 200 and
// every turn on that head kept being served, because nothing on the turn path read a budget. Each head
// is handed ONE ledger ([BudgetEnforcement.forHead], through HeadServerFactory): admit() before a
// turn, spent() after its perf row. `block` refuses the turn once today's spend has reached the limit;
// `warn` tells the operator once, through the saved alert webhook and the head's log, and serves it.
//
// TODAY IS THE UTC DAY, the boundary /api/projects' cost_today_usd already draws, so the console's
// figure and the budget weigh the same turns (TurnPrice prices them the same way too). A new day
// starts from nothing. A turn on a model with NO rate card has no price: it is not counted, and never
// silently — the head's log names the model once a day, and a refusal says how many it could not count.
//
// THE FILES: this one holds the two ports and the entry point; BudgetLedger.kt one head's ledger,
// BudgetDay.kt its day, TurnPrice.kt the price of a turn and BudgetText.kt every sentence it says.
package splice.usage.budgets

import splice.core.budget.HeadBudget
import splice.core.model.ModelCatalog
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource

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
) {
    private val context = LedgerContext(budgets, alert, log, clock, bootMs = clock())

    /** [head]'s ledger, pricing its turns against [catalog]'s rate cards. One per head: the key is
     *  bound here, so a head can never spend against another's budget. */
    public fun forHead(head: String, catalog: ModelCatalog?): HeadBudget =
        BudgetLedger(head, TurnPrice(catalog), history.rowsFor(head), context)
}
