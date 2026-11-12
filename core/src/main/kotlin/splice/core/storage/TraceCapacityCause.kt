// NEW: the trace store's refusals by name. The store says why it refused a write in a sentence, which goes to the log
// and onto the record. The console was matching those sentences to choose its words (Settings' "Saving stopped" card,
// the request view's "Not saved"), so a reworded sentence changed what the page said without anyone seeing it. The
// names here are what a route sends beside the sentence, and they are read off the sentences this file's neighbour
// writes, so the two cannot drift.
package splice.core.storage

/** Why the trace store refused a write. [wire] is the name a route sends; the sentence stays the store's own. */
public enum class TraceCapacityCause(public val wire: String, private val sentence: String) {
    /** The volume is down to the free space the store leaves. */
    DISK_FLOOR("disk_floor", FLOOR_REACHED),

    /** The budget for request records is spent by the day being written. */
    INDEX_BUDGET("index_budget", INDEX_CAP_REACHED),

    /** The rolling budget for prompts and answers is spent. */
    BODY_BUDGET("body_budget", BODY_BUDGET_SPENT),

    /** The day's prompts and answers were removed to make room for newer ones. */
    BODY_EVICTED("body_evicted", BODY_BUDGET_EVICTED_REASON),
    ;

    /** Whether [reason] is this cause's sentence, with or without what the store adds after it. */
    public fun names(reason: String): Boolean = reason.startsWith(sentence)
}

/** A refusal's sentence read back. A record written before the names existed carries only the sentence, and the
 *  floor's size travels inside it, so both are read from the text the store wrote. */
public class TraceCapacityReason(private val reason: String?) {
    /** The cause [reason] names, or null for no reason or one the store did not write. */
    public fun cause(): TraceCapacityCause? =
        reason?.let { text -> TraceCapacityCause.entries.firstOrNull { it.names(text) } }

    /** The free space the store leaves, when the floor was the cause and its sentence carries the size. */
    public fun floorBytes(): Long? = reason?.takeIf { cause() == TraceCapacityCause.DISK_FLOOR }
        ?.let { FLOOR_SIZE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
}

/** The size at the end of the floor's sentence, as VolumeFloor.reached writes it. */
private val FLOOR_SIZE = Regex("splice leaves (\\d+) B free")
