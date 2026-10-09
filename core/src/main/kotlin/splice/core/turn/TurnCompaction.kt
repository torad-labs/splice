package splice.core.turn

/** The compaction text a turn carries. */
public data class TurnCompaction(
    /** Effective custom compaction text and its scope [source]. Both stay null on ordinary turns;
     *  compact turns use null text for the untouched client default and empty text for opt-out. */
    val instructions: String? = null,
    val source: String? = null,
    /** sha256 of the provider body before any compaction tail: what a compaction retry is matched on
     *  (CompactionReplay), so a tail resolved differently on the retry cannot miss the recording. */
    val requestHash: String? = null,
)
