package splice.upstream.transport

import splice.upstream.retry.RateLimitCooldown

/** What bounds a post beyond its own retry rules: the selected account's cooldown and the outer turn's wait. */
public data class PostLimits(
    /** Selected account's cooldown; null preserves the client's legacy single-account cooldown. */
    val rateLimitCooldown: RateLimitCooldown? = null,
    /** Null preserves the legacy per-post deadline for callers without an outer turn budget. */
    val remainingTurnWait: RemainingTurnWait? = null,
)
