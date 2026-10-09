package splice.head.usage

/** One hour's token sums. Observed values only; the unreported-usage count beside them records the unknown turns. */
public data class BucketTokens(
    /** Every request's input the hour billed, cache buckets included: each turn's final round and the
     *  rounds it absorbed, the same three sums for [cachedTokens] and [cacheWriteTokens]. */
    val inTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** V4-86: the cache-WRITE half of [inTokens], disjoint from [cachedTokens] (the read half).
     *  Recorded BESIDE input, never out of it, for the same reason [cachedTokens] is: the plan
     *  meters total input and a written block bills in full. It is a separate sum because it
     *  bills at the vendor's cache_write rate, not the input rate. */
    val cacheWriteTokens: Long = 0,
    val outTokens: Long = 0,
)
