// PORT-OF: ManagedHead.kt — the head's persisted usage and rate-limit read (file truth), split out so
// the record that composes a head names each capability's source from its own file.
package splice.usage.quota

import splice.core.usage.QuotaView

/** Reads the head's persisted usage/ratelimit (file truth). */
public fun interface HeadUsageSource {
    /** One coherent filesystem read per control/statusline request. */
    public fun snapshot(): UsageView
}

/** The persisted `x-ratelimit-*` read. [observedAt] is the round that sent them, in epoch SECONDS
 *  (the unit the quota windows' `resets_at` uses), or null when the file names no observation. */
public data class RateLimitView(
    val limitTokens: Long?,
    val remainingTokens: Long?,
    val resetTokens: String?,
    val observedAt: Long? = null,
)
public data class UsageView(
    val outputTokens5h: Long,
    val entries: Int,
    val ratelimit: RateLimitView?,
    /** The provider's own plan windows, when the head has any (see QuotaTracker). */
    val quota: QuotaView? = null,
)
