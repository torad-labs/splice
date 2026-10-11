// NEW: V4-344's read-only source contract accounts for history, primary files and exclusions.
package splice.sessions.transcript

import java.nio.file.Path

/** One Claude Code config tree. [head] is null for the plain ~/.claude tree. */
public data class SessionHistoryRoot(val head: String?, val dir: Path)

/** One logical session, joined by id across a history row and a primary transcript file. */
public data class SessionHistoryEntry(
    val sessionId: String,
    val name: String?,
    val project: String?,
    val head: String?,
    val updatedAt: Long?,
    val files: SessionFiles,
)

/** Which on-disk sources know a session: a history row, a primary transcript file, or both. */
public data class SessionFiles(
    val hasHistory: Boolean,
    val hasTranscript: Boolean,
    /** An empty primary file remains in the census but cannot continue a conversation. */
    val resumable: Boolean = hasTranscript,
)

/** The denominator comes from the on-disk sources, not from a registry of running processes.
 *  [skipped] counts non-session artifacts by reason; [errors] names unreadable sources. */
public data class SessionHistoryScan(
    val sessions: List<SessionHistoryEntry>,
    val skipped: Map<String, Int> = emptyMap(),
    val errors: List<String> = emptyList(),
)

/** The live global transcript-view switch, consulted before any conversation reader opens a file. */
public fun interface SessionTranscriptViewEnabled {
    public operator fun invoke(): Boolean
}

/** Read-only source boundary. Paging and live-registry overlay belong to the sessions feature. */
public fun interface SessionHistorySource {
    public fun scan(roots: List<SessionHistoryRoot>): SessionHistoryScan
}
