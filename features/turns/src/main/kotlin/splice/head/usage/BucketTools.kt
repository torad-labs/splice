package splice.head.usage

/** One hour's tool-surface partition sums. */
public data class BucketTools(
    val toolsEager: Long = 0,
    val toolsDeferred: Long = 0,
    val deferralTurns: Long = 0,
)
