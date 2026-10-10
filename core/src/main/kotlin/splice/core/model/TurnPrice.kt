// NEW: V4-133 review — one turn's USD, priced the way the console already prices a day.
//
// The buckets are PerfTally's (/api/projects) and SessionCost's (the statusline): in_tokens INCLUDES
// both cache buckets (SessionCost.bucketsFor says why), so the fresh-input bucket is the difference,
// floored at 0. The card is the provider entry's own, the one /api/projects prices cost_today_usd
// with, so a budget and the console's figure weigh a turn the same. Posted bills need rates and
// observed buckets. An explicit empty no-request bill is exact zero without a rate card.
//
// V4-221: moved from features/usage (budgets) to core so the hourly economics rollup (features/turns)
// prices each turn with the SAME arithmetic the budget does; the two features share no edge but core.
package splice.core.model

public class TurnPrice(private val catalog: ModelCatalog?, private val cost: TokenCost = TokenCost()) {
    /** USD for the row's accounted requests, at the card for the hour [atMs] they ran in (a vendor that bills
     *  by the hour, [PeakHours]). Only an empty no-request bill needs no model rate card. */
    public fun usd(model: String?, counters: Map<String, Long>, atMs: Long? = null): Double? =
        TurnBill.usd(counters, model?.let(::ratesFor)?.at(atMs), cost)

    /** The reported tokens' lower-bound charge, even when a failed stream omitted its output. */
    public fun lowerBoundUsd(model: String?, counters: Map<String, Long>, atMs: Long? = null): Double? =
        TurnBill.lowerBoundUsd(counters, model?.let(::ratesFor)?.at(atMs), cost)

    /** Whether [model] has a rate card, so that a request on it with token counts gets a price. */
    public fun declares(model: String?): Boolean = model?.let(::ratesFor) != null

    private fun ratesFor(model: String): ModelRates? {
        val c = catalog?.live() ?: return null
        val key = c.stripSuffixes(model)
        return cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
    }
}
