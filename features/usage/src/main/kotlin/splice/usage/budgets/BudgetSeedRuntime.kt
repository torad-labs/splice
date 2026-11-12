// NEW: budget I/O borrows the daemon or test scope whose lifetime its caller already owns.
package splice.usage.budgets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker

/** History and settings reads run on I/O; this carrier never creates or owns a separate scope. */
public data class BudgetSeedRuntime(
    public val scope: CoroutineScope,
    public val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    public val ticker: Ticker = ProcessTicker(),
)
