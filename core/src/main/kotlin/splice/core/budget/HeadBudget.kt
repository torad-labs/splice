// NEW: V4-133 review — a head's daily spend budget as the turn path sees it.
//
// The console promises "`warn` tells the operator, `block` refuses the turn"
// (console/src/entities/budget/model/types.ts), and until this port nothing on the turn path read a
// budget at all: GET/PUT /api/budgets stored and served the rows, and every turn ran regardless.
//
// WHY IN CORE. The two sides of the port live in modules that cannot see each other: the head that
// admits and records a turn is :features-turns, the budgets and the alert settings are
// :features-usage, and neither depends on the other. Both depend on core, so the contract sits here
// and :app hands each head its own ledger (HeadServerFactory, from ConsoleEventPublisher.budgets).
package splice.core.budget

/** A refused turn: [message] is what the client is shown, [detail] is the log and perf-row detail. */
public data class BudgetBlock(val message: String, val detail: String)

/** One head's budget. EVERY METHOD RUNS ON THE TURN PATH and must never throw; an implementation
 *  that has to read history does it once per daemon lifetime, never per turn. */
public interface HeadBudget {
    /** Called once per turn before it is served. Null admits it; a [BudgetBlock] refuses it. A
     *  `warn` budget that has been reached tells the operator here and still admits the turn. */
    public fun admit(): BudgetBlock?

    /** A turn's perf row was written at [atMs] (wall clock) for [model] with [counters], the row's
     *  own token counters (splice.core.perf.PerfKeys). This is how the day's spend moves. */
    public fun spent(atMs: Long, model: String, counters: Map<String, Long>)
}

/** The head with no budget: a head built outside the daemon (tests, tools). Every production head
 *  gets a real one from HeadServerFactory, pinned by BudgetWiringPinTest. */
public object NoHeadBudget : HeadBudget {
    override fun admit(): BudgetBlock? = null

    override fun spent(atMs: Long, model: String, counters: Map<String, Long>): Unit = Unit
}
