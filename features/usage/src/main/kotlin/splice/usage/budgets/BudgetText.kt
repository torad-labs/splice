// NEW: V4-133 review — every sentence a budget says: the refusal a client reads, the alert an operator
// reads, and the amounts the log and the perf row carry. USD is two decimals in Locale.ROOT, never the
// locale's separator, so the same spend reads the same in all three.
package splice.usage.budgets

import splice.core.budget.BudgetBlock
import java.util.Locale

internal object BudgetText {
    /** The block refusal: the head, the day's spend, the limit, when it lifts, how to lift it now, and
     *  how many of today's turns it could not count. */
    fun refusal(head: String, tally: DayTally, limit: Double): BudgetBlock {
        val uncounted = when (tally.unpriced) {
            0L -> ""
            1L -> " 1 turn today ran on a model with no rate card and is not counted."
            else -> " ${tally.unpriced} turns today ran on a model with no rate card and are not counted."
        }
        return BudgetBlock(
            message = "splice refused this turn: head '$head' has spent ${dollars(tally.usd)} today (UTC) " +
                "against its ${dollars(limit)} daily budget, and the budget's action is block. New turns " +
                "on this head are refused until 00:00 UTC; raise or clear the budget in the splice console " +
                "to continue sooner.$uncounted",
            detail = "${amounts(tally.usd, limit)} unpriced_turns=${tally.unpriced}",
        )
    }

    /** The warn alert: the budget is reached and the head's turns continue. */
    fun warning(head: String, spent: Double, limit: Double): String =
        "splice: head '$head' reached its daily budget: ${dollars(spent)} spent today (UTC) against " +
            "${dollars(limit)}. Its action is warn, so its turns continue."

    /** The machine-readable pair the log line and the perf-row detail carry. */
    fun amounts(spent: Double, limit: Double): String = "spent_usd=${amount(spent)} limit_usd=${amount(limit)}"

    private fun dollars(usd: Double): String = "$" + amount(usd)

    private fun amount(usd: Double): String = String.format(Locale.ROOT, "%.2f", usd)
}
