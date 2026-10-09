package splice.usage.perf

/** The writer's five string-and-flag facts about one turn, as the row carries them (V4-127).
 *
 *  NULL MEANS THE ROW DOES NOT CARRY THE FIELD, deliberately distinguished from a false or empty
 *  value. [cacheCold] is written ONLY alongside an account (PerfStats.record), so a row with no
 *  account never had the question asked; reading that as `false` would report "the cache was warm"
 *  about a turn where nothing looked. [compact] and [model] are unconditional in the current writer,
 *  so null there means a LEGACY or torn row, and a payload omits the field rather than inventing one.
 *
 *  NAMED ARGUMENTS ARE THE CONTRACT at every construction site (the ModelRates scar, V4-127): four of
 *  these five are nullable and two of the strings are adjacent, so a POSITIONAL call that swaps
 *  session and account compiles, passes, and reports the wrong facts with a green suite. */
public data class PerfTurnFacts(
    val model: String? = null,
    val session: String? = null,
    val account: String? = null,
    val cacheCold: Boolean? = null,
    val compact: Boolean? = null,
)
