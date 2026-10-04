// NEW: V4-344 joins a durable session's source disposition to its live registry record.
package splice.sessions.http

import splice.sessions.registry.SessionAvailability
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
        return HistoryItem(id, record, source, entry?.resumable ?: false)
    }

    private fun source(): String? = when {
        entry?.hasHistory == true && entry.hasTranscript -> "history+transcript"
        entry?.hasHistory == true -> "history-only"
        entry?.hasTranscript == true -> "transcript-only"
        live != null -> "registry-only"
        else -> null
    }

    private fun historicalRecord(id: String): SessionRecord? = entry?.let { found ->
        SessionRecord(
            pid = null,
            sessionId = id,
            cwd = found.project,
            name = found.name,
            kind = null,
            version = null,
            status = SessionStatus(),
            startedAt = null,
            updatedAt = found.updatedAt,
            messagingSocketPath = null,
            route = found.head?.let(SessionRoute::Head) ?: SessionRoute.Unknown,
            availability = SessionAvailability.GONE,
        )
    }

    private fun overlay(current: SessionRecord, historical: SessionRecord?): SessionRecord = current.copy(
        cwd = current.cwd ?: historical?.cwd,
        name = current.name ?: historical?.name,
        updatedAt = listOfNotNull(current.updatedAt, historical?.updatedAt).maxOrNull(),
        route = when (current.route) {
            is SessionRoute.Head, SessionRoute.Direct -> current.route
            SessionRoute.Unknown -> historical?.route ?: SessionRoute.Unknown
        },
    )
}

/** Search and cursor keys are read from the same merged row the console ultimately shows. */
internal data class HistoryItem(val id: String, val record: SessionRecord, val source: String, val resumable: Boolean) {
    val at: Long get() = record.updatedAt ?: 0L

    /** The page after an immutable sort key, even if its anchor record was deleted or updated. */
    fun follows(cursor: Pair<Long, String>): Boolean {
        if (at < cursor.first) return true
        return at == cursor.first && id < cursor.second
    }

    fun matches(query: String): Boolean {
        if (query.isEmpty()) return true
        val lowered = query.lowercase(Locale.ROOT)
        return listOfNotNull(record.name, record.cwd, record.head, id)
            .any { it.lowercase(Locale.ROOT).contains(lowered) }
    }

    fun matchesRepo(query: String, root: String?): Boolean =
        root?.lowercase(Locale.ROOT)?.contains(query.lowercase(Locale.ROOT)) == true
}
