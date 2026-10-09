package splice.sessions.transcript

/** Which on-disk sources know a session: a history row, a primary transcript file, or both. */
public data class SessionFiles(
    val hasHistory: Boolean,
    val hasTranscript: Boolean,
    /** An empty primary file remains in the census but cannot continue a conversation. */
    val resumable: Boolean = hasTranscript,
)
