package splice.core.turn

/** Tool-surface partition sizes for THIS turn's request; both null when deferral was not in play.
 *  Non-null stamps the perf counters even at zero — a deploy where tools_deferred stays 0 is a
 *  false landing, and it must be visible in one grep of the perf JSONL. */
public data class TurnToolSurface(
    val eager: Int? = null,
    val deferred: Int? = null,
)
