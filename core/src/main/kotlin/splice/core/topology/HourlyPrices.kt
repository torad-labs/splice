// NEW: server mode (docs/specs/server-mode.md, F10 fixed-cost providers). The providers a team pays for by the hour,
// a rented GPU box whose bill runs whether or not anyone sends it a turn, as their `hourly_usd` says: what a usage
// report meters beside the rate cards, and what boot refuses in it.
package splice.core.topology

/** The `hourly_usd` of each of [providers] that sets one. */
public class HourlyPrices(private val providers: Map<String, ProviderConfig>) {
    /** Each provider paid for by the hour, by its key in splice.toml, with what it costs an hour in USD; sorted by
     *  key, so a report lists them in one order. */
    public fun prices(): Map<String, Double> =
        providers.mapNotNull { (key, provider) -> provider.hourlyUsd?.let { key to it } }.sortedBy { it.first }.toMap()

    /** An `hourly_usd` no report could meter: one below zero, or not a number at all (TOML spells `nan` and `inf`). */
    internal fun findings(): List<TopologyFinding> = providers.mapNotNull { (key, provider) ->
        val price = provider.hourlyUsd ?: return@mapNotNull null
        TopologyFinding("providers.$key.hourly_usd", "must be 0 or more: what the provider costs an hour, in USD")
            .takeUnless { price.isFinite() && price >= 0.0 }
    }
}
