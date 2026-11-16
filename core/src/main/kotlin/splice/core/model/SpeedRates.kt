// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 5) — a model's card at Fast or Ultrafast. OpenAI publishes one per
// tier per model on developers.openai.com/api/docs/pricing ("Fast" and "Ultrafast" tabs, read 2026-11-15): GPT-6
// Astra is $10 / $1 cached / $50 output at standard, $20 / $2 / $100 at Fast and $60 / $6 / $300 at Ultrafast. A turn
// is priced at the card for the speed the backend says it SERVED it at, and a speed with no card leaves the turn
// unpriced, never priced at the standard card ([ModelRates.atSpeed]).
//
// THE SHORT-CONTEXT CARD. The tabs price a request under the long-context threshold; a request past it at a speed has
// no card splice carries, so it is left unpriced too ([TurnBill]).
package splice.core.model

/** USD per MILLION tokens at one speed tier. [cacheWrite] null bills a cache write at [input], as [ModelRates] does. */
public data class SpeedRates(
    val input: Double,
    val cacheRead: Double,
    val output: Double,
    val cacheWrite: Double? = null,
) {
    /** This tier's card as the arithmetic reads one: one price at every request size and every hour. */
    public fun card(): ModelRates =
        ModelRates(input = input, cacheRead = cacheRead, output = output, cacheWrite = cacheWrite)

    /** This card at [factor] times its prices, as [ModelRates.at] scales a card inside its peak hours. */
    internal fun times(factor: Double): SpeedRates =
        SpeedRates(input * factor, cacheRead * factor, output * factor, cacheWrite?.times(factor))
}

/** A speed card as splice.toml spells it: flat keys on the model's card, each prefixed with the tier's word
 *  (`fast_input`, `ultrafast_cache_read`), for the reason the long-context keys are flat ([ModelRatesToml]). */
internal object SpeedCards {
    /** The card [prefix] names, or null when none of its keys is set. Some of them is a card that would price a turn
     *  at a half-declared tier, so it is refused where the config is read, naming what is missing. */
    fun of(prefix: String, input: Double?, cacheRead: Double?, output: Double?, cacheWrite: Double?): SpeedRates? {
        if (listOf(input, cacheRead, output, cacheWrite).all { it == null }) return null
        val incomplete = "a rate card's ${prefix}card needs ${prefix}input, ${prefix}cache_read and ${prefix}output " +
            "together (${prefix}cache_write is optional)"
        return SpeedRates(
            input = requireNotNull(input) { incomplete },
            cacheRead = requireNotNull(cacheRead) { incomplete },
            output = requireNotNull(output) { incomplete },
            cacheWrite = cacheWrite,
        )
    }
}
