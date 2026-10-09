package splice.head.usage

/** The request sizes one turn reported: what the client sent and what the head sent upstream. */
public data class TurnBytes(
    val reqBytes: Long?,
    val upstreamBytes: Long?,
)
