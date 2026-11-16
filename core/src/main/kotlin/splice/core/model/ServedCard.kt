// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 5) — the card a turn's perf row is billed at: the card for the speed
// the backend says it SERVED the turn at, which can be slower than the speed asked. A speed with no card, or a request
// past the long-context threshold at a speed (the published speed cards are short-context ones, SpeedRates), leaves the
// row unpriced, never priced at the standard card.
package splice.core.model

import splice.core.perf.SpeedCounters
import splice.core.speed.Speed

/** Which card prices a row, by the speed it was served at. Read by [TurnBill] for every pricer. */
internal object ServedCard {
    /** The card [row] is billed at, from the model's [standard] card (null: the model has none): [standard] on a row
     *  served at standard speed or naming no speed (a row from before the speed counters, or a backend that names
     *  none), else the speed's own card, or null when there is none to price it at. */
    fun of(row: Map<String, Long>, standard: ModelRates?): ModelRates? {
        val speed = SpeedCounters.served(row)?.takeUnless { it == Speed.STANDARD }
        if (standard == null || speed == null) return standard
        val long = standard.longContext?.let { requestPast(row, it.overInputTokens) } ?: false
        return standard.atSpeed(speed)?.takeUnless { long }
    }

    /** Whether a request of [row] was past [over] input tokens: its final one, or its absorbed ones at their mean. */
    private fun requestPast(row: Map<String, Long>, over: Long): Boolean {
        val final = TurnBill.last(row).requestInput
        val rounds = TurnBill.absorbed(row).rounds
        val earlier = TurnBill.total(row).requestInput - final
        return final > over || (rounds > 0 && earlier > over * rounds)
    }
}
