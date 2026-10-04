// NEW: V4-239 — GET /api/heads/{head}/trace[?last=N][&session=S][&turn=ID]: what `splice trace <head>`
// prints, for the console. It reads the SAME day files the verb reads, through TraceRows, and selects
// and summarises turns the same way, so the two cannot disagree about a turn:
//
//   no turn      {head, files, on_disk, skipped_lines, turns: [{id, ts, session, model, compact, open,
//                 outcome, rounds, attempts, total_ms}]}, the newest [last] (default 20), oldest first
//   ?turn=ID     {head, turn: <that summary>, cost_usd, records: [every record of the turn, as written]}
//                (V4-345: cost_usd is the ended turn priced at its model's card, null with no card, and
//                absent for an open turn)
//
// THE LIST CARRIES NO BODY. A body is the user's conversation; it leaves only for the one turn a reader
// opened, as `splice trace --turn` prints it. The records are the TraceStore's own, headers redacted by
// name when they were written (HeaderRedaction), bodies exact up to the cap.
//
// WHAT IS ON DISK, CAPTURE ON OR OFF. The files outlive the knob: a head whose trace was turned off
// keeps the days it recorded until retention deletes them, and the verb reads them either way, so this
// does too. Whether capture is on NOW is GET /api/heads/{head}/capture's answer; the console reads both.
// The CLI and the guarded trace/kept route delete through the same per-head DayFiles store.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapCapacityException
import splice.core.storage.DayFiles
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.wire.BAD_LAST
import splice.http.JsonReply
import java.nio.file.Path

internal const val TRACE_UNWIRED = "the daemon wired no trace directory; /api/heads/{head}/trace cannot read it"
internal const val TRACE_DELETED_STATE = "deleted"
internal const val TRACE_DELETED_REASON = "trace deleted"

/** The daemon's trace directory, where every head's TraceStore writes; read per request because the
 *  control plane is handed it after construction. */
public fun interface TraceDirPort {
    public operator fun invoke(): Path?
}

/** The query parameters, as they arrived: [last] a count, [session] a session id prefix, [turn] one
 *  turn's id. Blank reads as absent. */
public data class TraceQuery(val last: String?, val session: String?, val turn: String?)

/** [io] runs the file read off the server's own threads. */
public class TraceRoute(
    private val heads: TurnsHeadLookup,
    private val dir: TraceDirPort,
    private val io: CoroutineDispatcher,
) {
    private val rows = TraceRows()
    private val bodies = TraceReplyBodies()

    public suspend fun read(head: String, query: TraceQuery): JsonReply {
        val found = heads.byName(head).firstOrNull()
        val last = query.last?.takeIf { it.isNotBlank() }
        val count = if (last == null) DEFAULT_LAST else last.toIntOrNull()?.takeIf { it > 0 }
        val traceDir = dir()
        return when {
            found == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            count == null -> refuse(HttpStatusCode.BadRequest, BAD_LAST)
            traceDir == null -> refuse(HttpStatusCode.ServiceUnavailable, TRACE_UNWIRED)
            else -> answer(found, traceDir, query, count)
        }
    }

    /** V4-338: the list says how many turns are on disk, so it reads every line, holding one at a time and
     *  the records of the turns it lists; one turn's read stops once it holds that turn. Both run on [io]. */
    private suspend fun answer(head: TurnsHead, traceDir: Path, query: TraceQuery, last: Int): JsonReply {
        val turn = query.turn?.takeIf { it.isNotBlank() }
        val ask = TraceAsk(last, query.session?.takeIf { it.isNotBlank() }, turn)
        return if (turn == null) list(head.key, traceDir, ask) else one(head, traceDir, ask, turn)
    }

    private suspend fun list(key: String, traceDir: Path, ask: TraceAsk): JsonReply {
        val read = withContext(io) { Cancellables.runCatchingCancellable { rows.read(traceDir, key, ask) } }
            .getOrElse { return unreadable(key, traceDir, it) }
        return JsonReply(HttpStatusCode.OK, bodies.list(key, traceDir, read))
    }

    private suspend fun one(head: TurnsHead, traceDir: Path, ask: TraceAsk, turn: String): JsonReply {
        val key = head.key
        val turns = withContext(io) { Cancellables.runCatchingCancellable { rows.turns(traceDir, key, ask) } }
            .getOrElse { return unreadable(key, traceDir, it) }
        return if (turns.isEmpty()) {
            val reason = when {
                DayFiles(traceDir, key).deleted() -> TRACE_DELETED_REASON
                else -> "no turn $turn in $key's trace"
            }
            refuse(HttpStatusCode.BadRequest, reason)
        } else {
            JsonReply(HttpStatusCode.OK, bodies.turn(head, turns.single()))
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
