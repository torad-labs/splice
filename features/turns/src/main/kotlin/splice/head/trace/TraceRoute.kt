// NEW: V4-239 — GET /api/heads/{head}/trace?turn=ID: one turn's records, as `splice trace --turn` prints them, for
// the console: {records: [every record of the turn, as written]}. It reads the SAME day files the verb reads, through
// TraceRows, so the two cannot disagree about a turn. A read without a turn is refused: the console opens one turn
// at a time, and the verb lists turns from the files itself.
//
// A body is the user's conversation; it leaves only for the one turn a reader opened. The records are the
// TraceStore's own, headers redacted by name when they were written (HeaderRedaction), bodies exact up to the cap.
//
// WHAT IS ON DISK, CAPTURE ON OR OFF. The files outlive the knob: a head whose trace was turned off
// keeps the days it recorded until retention deletes them, and the verb reads them either way, so this
// does too. Whether capture is on NOW is GET /api/heads/{head}/capture's answer; the console reads both.
// The CLI and the guarded trace/kept route delete through the same per-head DayFiles store.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.storage.DayFiles
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.http.JsonReply
import splice.upstream.memory.JvmHeap
import java.nio.file.Path

internal const val TRACE_UNWIRED = "the daemon wired no trace directory; /api/heads/{head}/trace cannot read it"
internal const val TRACE_NO_TURN = "a trace read names one turn: ?turn=ID"
internal const val TRACE_DELETED_STATE = "deleted"
internal const val TRACE_DELETED_REASON = "trace deleted"

/** The daemon's trace directory, where every head's TraceStore writes; read per request because the
 *  control plane is handed it after construction. */
public fun interface TraceDirPort {
    public operator fun invoke(): Path?
}

/** The words to find in what a head's turns sent and got back: [q] the text, [since] the oldest instant to read back to
 *  (epoch ms), [limit] how many turns to answer with. Blank reads as absent. */
public data class TraceSearchQuery(val q: String?, val since: String?, val limit: String?)

/** [io] runs the file read off the server's own threads. */
public class TraceRoute(
    private val heads: TurnsHeadLookup,
    private val dir: TraceDirPort,
    private val io: CoroutineDispatcher,
    heap: HeapBudget = JvmHeap.budget,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    internal val rows = TraceRows(heap = heap)

    /** GET /api/heads/{head}/trace/search?q=&since=&limit=: the turns whose sent or returned body holds the words,
     *  newest first, with how far back the search read and why it stopped. */
    public suspend fun search(head: String, query: TraceSearchQuery): JsonReply =
        when (val ask = TraceSearchReader().read(query)) {
            is TraceSearchRead.Refused -> refuse(HttpStatusCode.BadRequest, ask.message)
            is TraceSearchRead.Read -> searching(head, ask.ask)
        }

    private suspend fun searching(head: String, ask: TraceSearchAsk): JsonReply {
        val found = heads.byName(head).firstOrNull()
        val traceDir = dir()
        return when {
            found == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            traceDir == null -> refuse(HttpStatusCode.ServiceUnavailable, TRACE_UNWIRED)
            else -> searched(found, traceDir, ask)
        }
    }

    private suspend fun searched(head: TurnsHead, traceDir: Path, ask: TraceSearchAsk): JsonReply =
        rows.withRead { share ->
            val read = withContext(io) {
                Cancellables.runCatchingCancellable {
                    TraceSearch(ask, clock, Json { ignoreUnknownKeys = true }, share).use { search ->
                        rows.days(traceDir, head.key).newestFirst(search)
                        search.found()
                    }
                }
            }.getOrElse { return unreadable(head.key, traceDir, it) }
            JsonReply(HttpStatusCode.OK, TraceReplyBodies(share).search(head, read))
        }

    /** GET /api/heads/{head}/trace?turn=ID: that turn's records, read on [io] from the newest line until whole. */
    public suspend fun read(
        head: String,
        turn: String?,
        outline: Boolean = false,
        document: TraceDocumentQuery? = null,
    ): JsonReply {
        val found = heads.byName(head).firstOrNull()
        val asked = turn?.takeIf { it.isNotBlank() }
        val traceDir = dir()
        return when {
            found == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            asked == null -> refuse(HttpStatusCode.BadRequest, TRACE_NO_TURN)
            traceDir == null -> refuse(HttpStatusCode.ServiceUnavailable, TRACE_UNWIRED)
            else -> one(found.key, traceDir, asked, outline, document)
        }
    }

    private suspend fun one(
        key: String,
        traceDir: Path,
        turn: String,
        outline: Boolean,
        document: TraceDocumentQuery?,
    ): JsonReply = rows.withRead { share ->
        val turns = withContext(io) {
            Cancellables.runCatchingCancellable {
                val ask = TraceAsk(1, turn = turn)
                if (outline || document != null) {
                    rows.references(traceDir, key, ask, share)
                } else {
                    rows.turns(traceDir, key, ask, share)
                }
            }
        }.getOrElse { return unreadable(key, traceDir, it) }
        if (turns.isEmpty()) {
            val reason = when {
                DayFiles(traceDir, key).deleted() -> TRACE_DELETED_REASON
                else -> "no turn $turn in $key's trace"
            }
            refuse(HttpStatusCode.BadRequest, reason)
        } else if (document != null) {
            document(turns.single(), traceDir, document, share)
        } else {
            val bodies = TraceReplyBodies(share)
            JsonReply(HttpStatusCode.OK, if (outline) bodies.outline(turns.single()) else bodies.turn(turns.single()))
        }
    }

    private suspend fun document(
        turn: TracedTurn,
        dir: Path,
        query: TraceDocumentQuery,
        share: HeapReservations,
    ): JsonReply = withContext(io) {
        try {
            JsonReply(HttpStatusCode.OK, TraceDocumentRead(share).read(turn, dir, query))
        } catch (failure: IllegalArgumentException) {
            refuse(HttpStatusCode.BadRequest, SafeFailureText.render(failure))
        } catch (failure: java.io.IOException) {
            refuse(HttpStatusCode.InternalServerError, SafeFailureText.render(failure))
        }
    }

    /** V4-286: a trace dir or day that cannot be read is said, never an empty list blaming the knob. */
    private fun unreadable(key: String, traceDir: Path, failure: Throwable): JsonReply {
        if (failure is HeapCapacityException) throw failure
        val why = SafeFailureText.render(failure)
        return refuse(HttpStatusCode.InternalServerError, "cannot read $key's trace under $traceDir: $why")
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.let(JsonWire::string))
}
