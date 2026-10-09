package splice.app.sources

/** The cumulative-drops evidence of one perf line: whether it may carry the counter, and the value if parsed. */
internal data class PerfDropsHint(
    val candidate: Boolean,
    val count: Long?,
)
