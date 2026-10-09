package splice.head.usage

/** The token counters one turn reported. Each is null when the head did not report it, which stays distinct
 *  from a real zero. NO defaults, for the reason [cacheWriteTokens] has none. */
public data class TurnTokens(
    val inTokens: Long?,
    val cachedTokens: Long?,
    /** V4-86: this turn's cache-write bucket, from PerfKeys.CACHE_WRITE_TOKENS. NO default on
     *  purpose — a default would let a new call site drop the most expensive bucket on the turn
     *  and still compile, which is exactly how the counter came to die at this seam. */
    val cacheWriteTokens: Long?,
    val outTokens: Long?,
)
