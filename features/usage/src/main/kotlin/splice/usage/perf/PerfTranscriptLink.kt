package splice.usage.perf

/** V4-354: what joins a perf row to the client's local transcript. */
public data class PerfTranscriptLink(
    /** The full session id, not the truncated tag the row's session fact holds. */
    val sessionId: String? = null,
    /** The response id the client recorded in its local transcript. */
    val responseMessageId: String? = null,
)
