// NEW: V4-133 review — every sentence a budget says: the refusal a client reads, the alert an operator
// reads, and the amounts the log and the perf row carry. USD is two decimals in Locale.ROOT, never the
// locale's separator, so the same spend reads the same in all three.
//
// V4-240: the day's spend is priced from rate cards, so it is an estimate of API cost and says so
// (ApiCostText); the budget beside it is the operator's own number and stays plain.
package splice.usage.budgets

import splice.core.budget.BudgetBlock
import splice.usage.ApiCostText

internal object BudgetText {
    /** The block refusal: the head, the day's spend, the limit, when it lifts, how to lift it now, and
     *  how many of today's turns it could not count. */
    fun refusal(head: String, tally: DayTally, limit: Double): BudgetBlock {
        val uncounted = when {
            tally.unpriced == 0L -> ""
            tally.inexactRows > 0L -> " ${tally.unpriced} turns could not be priced from their reported usage."
            else -> noCard(tally.unpriced)
        }
        val partial = when {
            !tally.seeded -> " Earlier spend is still loading, so this tally is partial."
            tally.inexactRows > 0L -> " Some spend is not reported, so this tally is only a lower bound."
            !tally.historyReadable -> " Earlier spend could not all be read, so this tally is partial."
            else -> ""
        }
        return BudgetBlock(
            message = "splice refused this turn: head '$head' has run up ${ApiCostText.sentence(tally.usd)} " +
                "today (UTC) against its ${ApiCostText.limit(limit)} daily budget, and the budget's action is " +
                "block. New turns on this head are refused until 00:00 UTC; to continue sooner, raise or remove its " +
                "limit in budgets.json in the splice state directory (an edit takes effect without a restart)." +
                "$uncounted$partial",
            detail = "${amounts(tally.usd, limit)} unpriced_turns=${tally.unpriced}" +
                if (partial.isEmpty()) "" else " spend_complete=false",
        )
    }

    private fun noCard(turns: Long): String = when (turns) {
        1L -> " 1 turn today ran on a model with no rate card and is not counted."
        else -> " $turns turns today ran on a model with no rate card and are not counted."
    }

    /** The warn alert: the budget is reached and the head's turns continue. */
    fun warning(head: String, spent: Double, limit: Double): String =
        "splice: head '$head' reached its ${ApiCostText.limit(limit)} daily budget with " +
            "${ApiCostText.sentence(spent)} today (UTC). Its action is warn, so its turns continue."

    /** The machine-readable pair the log line and the perf-row detail carry: keys, not a figure a
     *  person reads as a charge, so no basis rides beside them. */
    fun amounts(spent: Double, limit: Double): String =
        "spent_usd=${ApiCostText.amount(spent)} limit_usd=${ApiCostText.amount(limit)}"
}
