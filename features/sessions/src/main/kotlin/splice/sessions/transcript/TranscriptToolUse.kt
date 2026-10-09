package splice.sessions.transcript

/** The tool facts a message carries; every field is null on a message that is neither a call nor a result. */
public data class TranscriptToolUse(
    /** The tool's name: on a call it is the tool called, on a result the tool the result answers when that is known. */
    val name: String? = null,
    /** True on a tool result, false on the call; null on everything else. */
    val result: Boolean? = null,
    /** The client's tool-use id, carried on both the call and its result across page boundaries. */
    val id: String? = null,
)
