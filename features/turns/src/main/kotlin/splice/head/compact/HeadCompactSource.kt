// PORT-OF: ManagedHead.kt — the head's compaction stats read (file truth), split out so the record that
// composes a head names each capability's source from its own file.
package splice.head.compact

/** Reads the head's compaction stats (file truth). */
public interface HeadCompactSource {
    public fun summary(tailN: Int): CompactView
}

/** The time a head's counts cover. [total] and the outcome counts come from a bounded TAIL of the
 *  stats file, not its whole history, so they are only honest beside the oldest ([firstTs]) and newest
 *  ([lastTs]) rows they counted; [recent] is the outcomes of the last seven days among those rows. A
 *  head whose tail starts inside the seven days has every row of it counted. Console review 2026-09-24:
 *  an all-time count with no span read as "a third of compactions fail today". */
public data class CompactSpan(val firstTs: Long, val lastTs: Long, val recent: Map<String, Int>)

/** [span] is null when no row was read: there is no time to claim. */
public data class CompactView(
    val total: Int,
    val byOutcome: Map<String, Int>,
    val tail: List<Map<String, String>>,
    val span: CompactSpan? = null,
)
