// NEW: admission reads an immutable policy snapshot while one owned background refresh observes hand edits.
package splice.usage.budgets

import kotlinx.coroutines.launch

// why: a one-second interval bounds observation of hand edits without a filesystem operation per turn.
private const val BUDGET_POLICY_REFRESH_MS = 1_000L

/** Console replacements publish immediately; external edits refresh within one tick plus I/O completion. */
internal class BudgetPolicy(private val store: BudgetStore, private val runtime: BudgetSeedRuntime) {
    init {
        // Enforcement is assembled before a head accepts requests, so the initial policy is known at boot.
        store.refresh()
        runtime.scope.launch(runtime.dispatcher) {
            while (runtime.ticker.awaitTick(BUDGET_POLICY_REFRESH_MS)) store.refresh()
        }
    }

    fun forHead(head: String): Budget? = store.current().budgets.firstOrNull { it.head == head }
}
