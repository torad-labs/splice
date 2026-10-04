// NEW: the rounds a turn billed before its final one, kept so each is priced once.
package splice.core.turn

/**
 * The rounds a turn's usage absorbed before its final round. [Usage.inputTokens] is the final round's
 * input, the context the client reads. Each earlier round was billed as a request of its own, so its
 * buckets land here instead of being overwritten by the next round's. Like [Usage], [inputTokens]
 * includes both cache buckets. [outputTokens] is the share of [Usage.outputTokens] these rounds produced.
 */
public data class AbsorbedRounds(
    val rounds: Long = 0,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    public operator fun plus(other: AbsorbedRounds): AbsorbedRounds = AbsorbedRounds(
        rounds = rounds + other.rounds,
        inputTokens = inputTokens + other.inputTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        outputTokens = outputTokens + other.outputTokens,
    )
}
