package splice.usage.economics

/** The hour's dollars and the turns that could not be priced. */
public data class EconomicsCost(
    /** V4-221: the hour's dollars, each turn at its own model's card; null for an hour recorded before
     *  the daemon priced turns ("not priced then", never $0). */
    val costUsd: Double?,
    /** V4-221: turns whose model had no rate card, so their dollars are not in [costUsd]. */
    val unpricedTurns: Long,
)
