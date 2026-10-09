package splice.core.turn

/** Where a turn goes: whether the client asked for a stream, the model it named, the model the
 *  upstream is asked for, and the output ceiling the client set. */
public data class TurnRoute(
    val stream: Boolean,
    val originalModel: String,
    val upstreamModel: String,
    val clientMaxTokens: Long?,
)
