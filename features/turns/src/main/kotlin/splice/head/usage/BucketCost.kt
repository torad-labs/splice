package splice.head.usage

/** One hour's dollars and the turns that could not be priced. */
public data class BucketCost(
    /** V4-221: the hour's dollars, each turn priced at its own model's card. NULL for an hour read
     *  from a file written before the field existed — "not priced then", never $0 — and it stays null
     *  if this daemon adds turns to that same hour, because a sum missing the earlier turns would
     *  read as the hour's whole cost. */
    val costUsd: Double? = 0.0,
    /** V4-221: turns whose model had no rate card; their dollars are not in [costUsd]. */
    val unpricedTurns: Long = 0,
)
