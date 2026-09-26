// NEW: V4-240 review — what ONE read of a head's byte-bounded perf tail says about one client
// session, carried from the reader (features/turns PerfStats) to the status line's cost segment
// (features/usage SessionCost). Core because those two features share no edge but core, the same
// reason TurnPrice lives here (V4-221). V4-244 adds the session's running total, kept as each row is
// appended, which the same cost segment reads instead of the tail when it covers the session.
package splice.core.perf

/** One turn as its perf row recorded it: the [model] it ran on (null on a legacy or torn row that
 *  carries none) and the row's numeric fields. */
public data class PerfSessionTurn(val model: String?, val counters: Map<String, Long>)

/** One session's turns from ONE read of the tail, newest last. [tailStartMs] is the `ts` of the
 *  oldest row that read held, any session's, and only when the read did NOT reach the start of the
 *  head's perf history (the file is past the byte bound, or a rolled generation holds older rows).
 *  A session that began before it may have turns the bound cut, so a total summed from [turns] is
 *  then a lower bound. Null when nothing older exists to miss, or the read held no dated row. */
public data class PerfSessionTail(val turns: List<PerfSessionTurn>, val tailStartMs: Long?)

/** V4-244: one model's share of a session's running total, summed as each of its rows was appended.
 *  The token counters are the perf row's own (in_tokens INCLUDES both cache buckets). [usd] is the
 *  dollars of the turns priced at this model's card when their rows were appended; [unpricedTurns]
 *  had no card then, and their dollars are not in [usd]. */
public data class PerfModelTotal(
    val turns: Long,
    val inTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val outTokens: Long,
    val usd: Double,
    val unpricedTurns: Long,
)

/** V4-244: one client session's running total, by the model each turn ran on. It holds every row of
 *  the session appended at or after [fromMs]; a session that began earlier may have rows from before
 *  the total existed, and a sum of [models] is then only a lower bound. */
public data class PerfSessionTotal(val fromMs: Long, val models: Map<String, PerfModelTotal>)
