// NEW: V4-240 review — what ONE read of a head's byte-bounded perf tail says about one client
// session, carried from the reader (features/turns PerfStats) to the status line's cost segment
// (features/usage SessionCost). Core because those two features share no edge but core, the same
// reason TurnPrice lives here (V4-221).
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
