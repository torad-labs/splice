package splice.usage.economics

/** The hour's token sums: what went in, the part of it served from cache, the part written to it, and what came out. */
public data class EconomicsTokens(
    val inTokens: Long,
    val cachedTokens: Long,
    /** V4-86: the cache-WRITE half of [inTokens], disjoint from [cachedTokens]. Its own sum
     *  because it bills at the vendor's cache_write rate and not at the input rate. */
    val cacheWriteTokens: Long,
    val outTokens: Long,
)
