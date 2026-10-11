// NEW: V4-344 joins a durable session's source disposition to its live registry record.
package splice.sessions.http

import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import splice.sessions.transcript.SessionHistoryEntry
import java.util.Locale

/** A logical session carries one durable disposition even when the live registry also knows it. */
internal data class JoinedSession(val entry: SessionHistoryEntry?, val live: SessionRecord?) {
    fun item(id: String): HistoryItem? {
        val source = source() ?: return null
        val historical = historicalRecord(id)
        val record = live?.let { overlay(it, historical) } ?: historical ?: return null
        return HistoryItem(id, record, source, entry?.files?.resumable ?: false)
    }

    private fun source(): String? = when {
        entry?.files?.hasHistory == true && entry.files.hasTranscript -> "history+transcript"
        entry?.files?.hasHistory == true -> "history-only"
        entry?.files?.hasTranscript == true -> "transcript-only"
        live != null -> "registry-only"
        else -> null
    }

    private fun historicalRecord(id: String): SessionRecord? = entry?.let { found ->
        SessionRecord(
            sessionId = id,
            name = found.name,
            status = SessionStatus(),
            route = found.head?.let(SessionRoute::Head) ?: SessionRoute.Unknown,
            availability = SessionAvailability.GONE,
            process = SessionProcess(
                pid = null,
                cwd = found.project,
                startedAt = null,
                updatedAt = found.updatedAt,
                messagingSocketPath = null,
            ),
            client = SessionClient(kind = null, version = null),
        )
    }

    private fun overlay(current: SessionRecord, historical: SessionRecord?): SessionRecord = current.copy(
        name = current.name ?: historical?.name,
        route = when (current.route) {
            is SessionRoute.Head, SessionRoute.Direct -> current.route
            SessionRoute.Unknown -> historical?.route ?: SessionRoute.Unknown
        },
        process = current.process.copy(
            cwd = current.process.cwd ?: historical?.process?.cwd,
            updatedAt = listOfNotNull(current.process.updatedAt, historical?.process?.updatedAt).maxOrNull(),
        ),
    )
}

/** Search and cursor keys are read from the same merged row the console ultimately shows. */
internal data class HistoryItem(val id: String, val record: SessionRecord, val source: String, val resumable: Boolean) {
    val at: Long get() = record.process.updatedAt ?: 0L

    /** The page after an immutable sort key, even if its anchor record was deleted or updated. */
    fun follows(cursor: Pair<Long, String>): Boolean {
        if (at < cursor.first) return true
        return at == cursor.first && id < cursor.second
    }

    fun matches(query: String): Boolean {
        if (query.isEmpty()) return true
        val lowered = query.lowercase(Locale.ROOT)
        return listOfNotNull(record.name, record.process.cwd, record.head, id)
            .any { it.lowercase(Locale.ROOT).contains(lowered) }
    }

    fun matchesRepo(query: String, root: String?): Boolean =
        root?.lowercase(Locale.ROOT)?.contains(query.lowercase(Locale.ROOT)) == true
}
