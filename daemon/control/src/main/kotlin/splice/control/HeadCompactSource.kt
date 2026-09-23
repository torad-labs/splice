// PORT-OF: ManagedHead.kt — the head's compaction stats read (file truth), split out so the record that
// composes a head names each capability's source from its own file.
package splice.control

/** Reads the head's compaction stats (file truth). */
public interface HeadCompactSource {
    public fun summary(tailN: Int): CompactView
}

public data class CompactView(val total: Int, val byOutcome: Map<String, Int>, val tail: List<Map<String, String>>)
