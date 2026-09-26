// NEW: V4-239 — GET /api/heads/{head}/trace[?last=N][&session=S][&turn=ID]: what `splice trace <head>`
// prints, for the console. It reads the SAME day files the verb reads, through TraceRows, and selects
// and summarises turns the same way, so the two cannot disagree about a turn:
//
//   no turn      {head, files, on_disk, skipped_lines, turns: [{id, ts, session, model, compact, open,
//                 outcome, rounds, attempts, total_ms}]}, the newest [last] (default 20), oldest first
//   ?turn=ID     {head, turn: <that summary>, records: [every record of the turn, as written]}
//
// THE LIST CARRIES NO BODY. A body is the user's conversation; it leaves only for the one turn a reader
// opened, as `splice trace --turn` prints it. The records are the TraceStore's own, headers redacted by
// name when they were written (HeaderRedaction), bodies exact up to the cap.
//
// WHAT IS ON DISK, CAPTURE ON OR OFF. The files outlive the knob: a head whose trace was turned off
// keeps the days it recorded until retention deletes them, and the verb reads them either way, so this
// does too. Whether capture is on NOW is GET /api/heads/{head}/capture's answer; the console reads both.
// Deleting them (`splice trace --purge`) stays on the CLI.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.TurnsHeadLookup
import splice.head.wire.BAD_LAST
import splice.http.JsonReply
import java.nio.file.Path

internal const val TRACE_UNWIRED = "the daemon wired no trace directory; /api/heads/{head}/trace cannot read it"

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

    public suspend fun read(head: String, query: TraceQuery): JsonReply {
        val key = heads.byName(head).firstOrNull()?.key
        val last = query.last?.takeIf { it.isNotBlank() }
        val count = if (last == null) DEFAULT_LAST else last.toIntOrNull()?.takeIf { it > 0 }
        val traceDir = dir()
        return when {
            key == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            count == null -> refuse(HttpStatusCode.BadRequest, BAD_LAST)
            traceDir == null -> refuse(HttpStatusCode.ServiceUnavailable, TRACE_UNWIRED)
            else -> answer(key, traceDir, query, count)
        }
    }

    private suspend fun answer(key: String, traceDir: Path, query: TraceQuery, last: Int): JsonReply {
        // V4-286: a trace dir or day that cannot be read is said, never an empty list blaming the knob.
        val read = withContext(io) { Cancellables.runCatchingCancellable { rows.read(traceDir, key) } }
            .getOrElse { failure ->
                val why = SafeFailureText.render(failure)
                return refuse(HttpStatusCode.InternalServerError, "cannot read $key's trace under $traceDir: $why")
            }
        val turn = query.turn?.takeIf { it.isNotBlank() }
        val selected = read.selected(query.session?.takeIf { it.isNotBlank() }, turn)
        return when {
            turn == null -> JsonReply(HttpStatusCode.OK, listJson(key, traceDir, selected.takeLast(last), read))
            selected.isEmpty() -> refuse(HttpStatusCode.BadRequest, "no turn $turn in $key's trace")
            else -> JsonReply(HttpStatusCode.OK, turnJson(key, selected.single()))
        }
    }

    private fun listJson(key: String, traceDir: Path, turns: List<TracedTurn>, read: TraceRead): String =
        buildJsonObject {
            put("head", key)
            put("files", "$traceDir/$key-YYYY-MM-DD.jsonl")
            put("on_disk", read.turns.size)
            put("skipped_lines", read.skippedLines)
            putJsonArray("turns") { turns.forEach { add(summary(it)) } }
        }.toString()

    private fun turnJson(key: String, turn: TracedTurn): String = buildJsonObject {
        put("head", key)
        put("turn", summary(turn))
        putJsonArray("records") {
            turn.attempts.forEach { add(it) }
            turn.turn?.let { add(it) }
        }
    }.toString()

    /** The verb's table line as fields. An open turn has no record to close it: its rounds and attempts
     *  are the attempts on disk, as the verb prints them. */
    private fun summary(turn: TracedTurn): JsonObject = buildJsonObject {
        val ending = turn.ending
        val open = turn.attempts.size
        put("id", turn.id)
        put("ts", turn.ts)
        put("session", turn.session)
        put("model", turn.model)
        put("compact", turn.compact)
        put("open", ending == null)
        put("outcome", ending?.outcome)
        put("rounds", if (ending == null) open.toLong() else ending.rounds.toLongOrNull())
        put("attempts", if (ending == null) open.toLong() else ending.attempts.toLongOrNull())
        put("total_ms", ending?.totalMs?.toLongOrNull())
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
