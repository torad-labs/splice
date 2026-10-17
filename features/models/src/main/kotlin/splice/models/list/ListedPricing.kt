// NEW: 2026-09-29 — V4-438: the card an endpoint LISTS for a model, read into ModelRates, so a model is
// priced wherever the provider's own list carries a price, not only where splice.toml or a profile wrote one.
//
// The shape is OpenRouter's `pricing` object on each row of GET /models (read 2026-09-29, 464 models):
// prompt and completion per TOKEN as decimal strings, input_cache_read and input_cache_write on the models
// that cache, and overrides[] for a tier that starts at min_prompt_tokens (80 models, five with two tiers).
// Prices are strings so no float drift enters before the one conversion to USD per MILLION tokens, which
// is a decimal point move on a BigDecimal.
//
// A price splice cannot read is NO card, never a guess: "-1" is how OpenRouter lists a price that depends on
// the route it picks (openrouter/auto), and a made-up rate would put a wrong number on a budget where "no
// rate card" is the honest one. A listed "0" IS a price: a free model costs nothing.
package splice.models.list

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.model.LongContextRates
import splice.core.model.ModelRates
import splice.core.util.JsonScalars
import java.math.BigDecimal

internal object ListedPricing {

    // why: OpenRouter lists a price per TOKEN and a ModelRates card is USD per MILLION tokens, so the decimal
    // point moves six places; a BigDecimal move is exact where multiplying a double by 1e6 is not.
    private const val PER_MILLION = 6

    /** The card [row]'s `pricing` lists, or null when it lists none or a price that is not a plain,
     *  non-negative number on either side of the turn. */
    fun of(row: JsonObject): ModelRates? {
        val pricing = row["pricing"] as? JsonObject ?: return null
        val (input, output) = sides(pricing) ?: return null
        return ModelRates(
            input = input,
            cacheRead = perMillion(pricing, "input_cache_read") ?: input,
            output = output,
            cacheWrite = perMillion(pricing, "input_cache_write"),
            longContext = tier(pricing["overrides"]),
        )
    }

    /** The size tier, from the overrides that start at a prompt size. An override that names a time of day
     *  and no size is a peak price no request size selects, so it is not a tier. A card carries ONE tier and
     *  the lowest threshold is the one most requests past a boundary reach (a request over the higher one is
     *  priced at the lower tier: an underestimate, on five models of 464). */
    private fun tier(overrides: JsonElement?): LongContextRates? =
        (overrides as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::sizeTier) }
            .minByOrNull { it.overInputTokens }

    private fun sizeTier(override: JsonObject): LongContextRates? {
        val over = JsonScalars.long(override, "min_prompt_tokens")?.takeIf { it > 0 } ?: return null
        val (input, output) = sides(override) ?: return null
        return LongContextRates(
            overInputTokens = over,
            input = input,
            cacheRead = perMillion(override, "input_cache_read") ?: input,
            output = output,
            cacheWrite = perMillion(override, "input_cache_write"),
        )
    }

    /** The prompt and completion prices, both or neither: a card with one side missing is no card. */
    private fun sides(from: JsonObject): Pair<Double, Double>? {
        val input = perMillion(from, "prompt") ?: return null
        val output = perMillion(from, "completion") ?: return null
        return input to output
    }

    /** USD per million tokens from a per-token decimal string, or null when it is absent, not a number, or
     *  negative. */
    private fun perMillion(from: JsonObject, key: String): Double? =
        JsonScalars.str(from, key)?.trim()?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 }
            ?.movePointRight(PER_MILLION)
            ?.let(BigDecimal::toDouble)
}
