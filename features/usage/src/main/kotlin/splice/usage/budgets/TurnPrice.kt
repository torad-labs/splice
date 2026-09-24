// NEW: V4-133 review — one turn's USD, priced the way the console already prices a day.
//
// The buckets are PerfTally's (/api/projects) and SessionCost's (the statusline): in_tokens INCLUDES
// both cache buckets (SessionCost.bucketsFor says why), so the fresh-input bucket is the difference,
// floored at 0. The card is the provider entry's own, the one /api/projects prices cost_today_usd
// with, so a budget and the console's figure weigh a turn the same. Null is "no rate card", never 0.
package splice.usage.budgets

import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.PerfKeys

internal class TurnPrice(private val catalog: ModelCatalog?, private val cost: TokenCost = TokenCost()) {
    /** USD for one perf row's [counters] on [model], or null when the model has no rate card. */
    fun usd(model: String?, counters: Map<String, Long>): Double? {
        val rates = model?.let(::ratesFor) ?: return null
        val cached = counters[PerfKeys.CACHED_TOKENS] ?: 0L
        val written = counters[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L
        val buckets = TokenBuckets(
            input = ((counters[PerfKeys.IN_TOKENS] ?: 0L) - cached - written).coerceAtLeast(0L),
            cacheRead = cached,
            cacheWrite = written,
            output = counters[PerfKeys.OUT_TOKENS] ?: 0L,
        )
        return cost.of(buckets, rates)
    }

    private fun ratesFor(model: String): ModelRates? {
        val c = catalog ?: return null
        val key = c.stripSuffixes(model)
        return cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
    }
}
