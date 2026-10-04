// NEW: V4-133 review — one turn's USD, priced the way the console already prices a day.
//
// The buckets are PerfTally's (/api/projects) and SessionCost's (the statusline): in_tokens INCLUDES
// both cache buckets (SessionCost.bucketsFor says why), so the fresh-input bucket is the difference,
// floored at 0. The card is the provider entry's own, the one /api/projects prices cost_today_usd
// with, so a budget and the console's figure weigh a turn the same. Null is "no rate card", never 0.
//
// V4-221: moved from features/usage (budgets) to core so the hourly economics rollup (features/turns)
// prices each turn with the SAME arithmetic the budget does; the two features share no edge but core.
package splice.core.model

public class TurnPrice(private val catalog: ModelCatalog?, private val cost: TokenCost = TokenCost()) {
    /** USD for every request one perf row's [counters] billed on [model] ([TurnBill]), or null when the
     *  model has no rate card. */
    public fun usd(model: String?, counters: Map<String, Long>): Double? {
        val rates = model?.let(::ratesFor) ?: return null
        return TurnBill.usd(counters, rates, cost)
    }

    /** Whether [model] has a rate card, so that a request on it with token counts gets a price. */
    public fun declares(model: String?): Boolean = model?.let(::ratesFor) != null

    private fun ratesFor(model: String): ModelRates? {
        val c = catalog?.live() ?: return null
        val key = c.stripSuffixes(model)
        return cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
    }
}
