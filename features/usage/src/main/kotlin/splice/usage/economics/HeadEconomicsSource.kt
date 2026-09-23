// PORT-OF: ManagedHead.kt — the head's hourly token-economics read (file truth), split out so the record
// that composes a head names each capability's source from its own file.
package splice.usage.economics

/** Reads the head's hourly token-economics rollup (file truth, oldest first). Separate from
 *  [HeadPerfSource] on purpose: perf answers "where did the latency go" from a bounded TAIL,
 *  economics answers "what has this cost against the plan" and needs SUMS over a week — a figure
 *  no percentile over the last few hundred turns can reconstruct. */
public fun interface HeadEconomicsSource {
    public fun buckets(): List<EconomicsRow>
}

/** One hour of a head's economics on the control-plane side. Sums only; every ratio the dashboard
 *  shows is derived at render time from these. [deferralTurns] is the denominator for the tool
 *  averages and is 0 on a head whose dialect cannot defer — which the UI renders as "n/a". */
public data class EconomicsRow(
    val hour: Long,
    val turns: Long,
    val inTokens: Long,
    val cachedTokens: Long,
    /** V4-86: the cache-WRITE half of [inTokens], disjoint from [cachedTokens]. Its own sum
     *  because it bills at the vendor's cache_write rate and not at the input rate. */
    val cacheWriteTokens: Long,
    val outTokens: Long,
    val reqBytes: Long,
    val upstreamBytes: Long,
    val toolsEager: Long,
    val toolsDeferred: Long,
    val deferralTurns: Long,
    val rateLimited: Long,
)
