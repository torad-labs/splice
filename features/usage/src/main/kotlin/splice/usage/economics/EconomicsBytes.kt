package splice.usage.economics

/** The hour's body sizes: what the client sent and what splice sent upstream. */
public data class EconomicsBytes(
    val reqBytes: Long,
    val upstreamBytes: Long,
)
