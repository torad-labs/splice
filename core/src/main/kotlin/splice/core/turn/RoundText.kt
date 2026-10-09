package splice.core.turn

/** The text a round produced, and what of it reached the client. */
public data class RoundText(
    val thinkingText: String = "",
    val bodyText: String = "",
    val emittedText: Boolean = false,
    /** True when a THINKING block actually reached the sink this round (CX-09).
     *
     *  Distinct from [thinkingText] being non-empty: the harvest fallback fills the buffer from
     *  the completed response object WITHOUT touching the sink, so the buffer is a statement
     *  about what the model produced and this is a statement about what the CLIENT received.
     *  Only the latter can answer "did this turn put anything on the wire", which is the
     *  question the empty-turn honesty gate has to ask before calling a turn empty. On a partial
     *  round it survives the buffered-round strip, because BufferingWireSink forwards openThinking /
     *  thinkingDelta straight to the real sink — only text and tool ops are held back. */
    val emittedThinking: Boolean = false,
)
