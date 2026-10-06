// NEW: V4-344 serves paged session history from Claude Code's durable sources with live overlay.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.http.JsonReply
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import splice.sessions.transcript.SessionTranscriptViewEnabled

/** Shared off response: no transcript body or derived title has been read when this is returned. */
internal object SessionTranscriptOff {
    val reply: JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("state", "off")
            put("reason", "Transcript view is off. Turn it on in Request detail.")
        }.toString(),
    )
}

// why: one screen of recent sessions loads without asking for the whole history.
private const val DEFAULT_HISTORY_PAGE = 50

// why: a caller cannot force a week of history into one browser response.
private const val MAX_HISTORY_PAGE = 100

// why: bound case-folded search and its reflected fault text at the HTTP boundary.
private const val MAX_SEARCH_CHARS = 200
private val HISTORY_CURSOR = Regex("(-?[0-9]+):([A-Za-z0-9_-]{1,128})")

/** The canonical repository name for a session, including a linked worktree's shared checkout. */
public fun interface SessionRepoNameOf {
    public fun root(record: SessionRecord): String?
}

/** The existing sessions row serializer, shared by the live and historical routes. */
public fun interface SessionHistoryRowOf {
    public operator fun invoke(record: SessionRecord): JsonObject

    public fun forRecords(records: List<SessionRecord>): SessionHistoryRowOf = this
}

/** Paged, searchable durable history with the live registry overlaid by session id. The source
 *  enumerates files; this feature owns the selection, cursor and console wire contract. */
public class SessionHistoryRoute(
    private val registry: SessionSource,
    private val source: SessionHistorySource,
    private val roots: List<SessionHistoryRoot>,
    private val rowOf: SessionHistoryRowOf,
    private val viewEnabled: SessionTranscriptViewEnabled = SessionTranscriptViewEnabled { true },
    private val repoOf: SessionRepoNameOf = SessionRepoNameOf { null },
) {
    public fun page(query: String?, cursor: String?, limit: Int?): JsonReply {
        if (!viewEnabled()) return SessionTranscriptOff.reply
        return enabledPage(query, cursor, limit)
    }

    private fun enabledPage(query: String?, cursor: String?, limit: Int?): JsonReply {
        val request = HistoryRequestParser.parse(query, cursor, limit)
            ?: return refused("invalid session search or cursor")
        val scan = source.scan(roots)
        val listing = registry.list()
        val slice = request.slice(rows(scan, listing.sessions, request.query))
        return JsonReply(HttpStatusCode.OK, pageJson(scan, slice, listing.error).toString())
    }

    private fun rows(scan: SessionHistoryScan, liveSessions: List<SessionRecord>, query: String): List<HistoryItem> {
        val records = linkedMapOf<String, JoinedSession>()
        scan.sessions.forEach { entry -> records[entry.sessionId] = JoinedSession(entry, null) }
        liveSessions.forEach { live ->
            val id = live.sessionId ?: return@forEach
            val previous = records[id]
            if (previous?.live == null) records[id] = JoinedSession(previous?.entry, live)
        }
        return records.mapNotNull { (id, joined) -> joined.item(id) }
            .filter { item -> item.matches(query) || item.matchesRepo(query, repoOf.root(item.record)) }
            .sortedWith(compareByDescending<HistoryItem> { it.at }.thenByDescending { it.id })
    }

    private fun pageJson(scan: SessionHistoryScan, slice: HistorySlice, registryError: String?): JsonObject =
        buildJsonObject {
            val rows = rowOf.forRecords(slice.sessions.map { it.record })
            put("sessions", buildJsonArray { slice.sessions.forEach { add(itemJson(it, rows)) } })
            put("next", slice.next)
            put("skipped", buildJsonObject { scan.skipped.forEach { (reason, count) -> put(reason, count) } })
            val errors = scan.errors + listOfNotNull(registryError)
            put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
        }

    private fun itemJson(item: HistoryItem, rows: SessionHistoryRowOf): JsonObject {
        val fields = rows(item.record).toMutableMap()
        fields["source"] = JsonPrimitive(item.source)
        fields["resumable"] = JsonPrimitive(item.resumable)
        return JsonObject(fields)
    }

    private fun refused(message: String): JsonReply = JsonReply(
        HttpStatusCode.BadRequest,
        buildJsonObject { put("error", message) }.toString(),
    )
}

private data class HistorySlice(val sessions: List<HistoryItem>, val next: String?)

private data class HistoryRequest(val query: String, val after: Pair<Long, String>?, val limit: Int) {
    fun slice(rows: List<HistoryItem>): HistorySlice {
        val start = after?.let { cursor ->
            rows.indexOfFirst { it.follows(cursor) }.takeIf { it >= 0 } ?: rows.size
        } ?: 0
        val page = rows.drop(start).take(limit)
        val next = if (start + page.size < rows.size) page.lastOrNull()?.let { "${it.at}:${it.id}" } else null
        return HistorySlice(page, next)
    }
}

private object HistoryRequestParser {
    fun parse(query: String?, cursor: String?, limit: Int?): HistoryRequest? {
        val needle = query.orEmpty().trim()
        if (needle.length > MAX_SEARCH_CHARS) return null
        val after = if (cursor == null) null else parseCursor(cursor) ?: return null
        return HistoryRequest(
            needle,
            after,
            (limit ?: DEFAULT_HISTORY_PAGE).coerceIn(1, MAX_HISTORY_PAGE),
        )
    }

    private fun parseCursor(cursor: String): Pair<Long, String>? {
        val match = HISTORY_CURSOR.matchEntire(cursor) ?: return null
        val at = match.groupValues[1].toLongOrNull() ?: return null
        return at to match.groupValues[2]
    }
}
