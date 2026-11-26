package splice.core.turn

/** An offer-only port for one turn. Implementations must not wait or do I/O; only offer to a bounded queue. */
public interface TurnObservation {
    /** Check interest before counting bytes; a subscriber can leave while a turn is streaming. */
    public val wantsFrames: Boolean get() = true
    public val wantsRequests: Boolean get() = true

    public fun requestSent(facts: ObservedRequest)
    public fun responseReceived(facts: ObservedResponse)
    public fun frame(facts: ObservedStreamFrame)
    public fun toolCall(facts: ObservedToolCall)
    public fun completed(facts: ObservedTurnEnd)
}

/** A logical round, before transport retries. Counts the uncompressed UTF-8 request bytes. */
public data class ObservedRequest(val round: Int, val model: String, val bytes: Long, val atEpochMs: Long)

/** The original HTTP status, before body processing. Attempt is the one-based turn-wide transport ordinal. */
public data class ObservedResponse(val status: Int, val attempt: Long, val atEpochMs: Long)

/** A frame at the client tap, numbered from one. No frame data is exposed. */
public data class ObservedStreamFrame(val seq: Long, val type: String, val bytes: Long, val atEpochMs: Long)

/** A client tool block opened by the model answer, not confirmation that the client ran it. */
public data class ObservedToolCall(val id: String, val name: String, val atEpochMs: Long)

/** The turn's own perf row outcome and duration, without the rest of the row. */
public data class ObservedTurnEnd(
    val outcome: String,
    val usage: ObservedUsage,
    val durationMs: Long,
    val atEpochMs: Long,
)

/** Perf-row usage: input is inclusive of cache reads and writes. */
public data class ObservedUsage(val input: Long, val output: Long, val cacheRead: Long, val cacheCreation: Long)
