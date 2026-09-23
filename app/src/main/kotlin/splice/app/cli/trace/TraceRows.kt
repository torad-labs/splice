// NEW: V4-174 — the read side of a head's trace files, for `splice trace`: the lines of every
// retained day, parsed and grouped by turn, oldest first. Reads the SAME day files the daemon's
// TraceStore writes (StatePaths.traceDir, `<head>-YYYY-MM-DD.jsonl`) through the same ActivityDays,
// so the CLI needs no daemon — the perf/logs idiom — and can never disagree with the writer about
// where a head's trace lives. A line this reader cannot place is counted, not fatal: a torn
// append heals on the next write (JsonlSink) and the operator is told how many were skipped.
package splice.app.cli.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.storage.ActivityDays
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.head.wire.TraceKinds
import java.nio.file.Path

/** Every record of one turn, in the order they were written: the attempts, then the turn record
 *  (null when the turn has not ended yet, or its ending was lost to the file lane). */
internal data class TracedTurn(val id: String, val attempts: List<JsonObject>, val turn: JsonObject?) {
    init {
        // `first` is the record every column is read off, so a turn holding neither an attempt
        // nor a turn record has nothing to show. TraceRows groups only records it placed under
        // an id; this is that contract, stated where a future producer has to meet it.
        require(turn != null || attempts.isNotEmpty()) { "traced turn $id holds no records" }
    }

    val first: JsonObject get() = turn ?: attempts.first()
    val ts: Long get() = JsonScalars.long(first, "ts") ?: 0L
    val session: String? get() = JsonScalars.str(first, "session")
}

internal data class TraceRead(val turns: List<TracedTurn>, val skippedLines: Int)

/** Far past any retention a head is configured with: the reader shows everything on disk. */
private const val EVERY_DAY_ON_DISK = 36_500

internal class TraceRows(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** The head's day store, over everything on disk (retention is the daemon's business). */
    internal fun days(traceDir: Path, head: String): ActivityDays = ActivityDays(traceDir, head, EVERY_DAY_ON_DISK)

    /** Every turn on disk for [head], oldest first; a turn's records may straddle a UTC midnight,
     *  which is why grouping happens over the whole read rather than per file. */
    internal fun read(traceDir: Path, head: String): TraceRead {
        var skipped = 0
        val byTurn = LinkedHashMap<String, Pair<MutableList<JsonObject>, JsonObject?>>()
        for (line in days(traceDir, head).lines()) {
            val record = parse(line)
            if (record == null || !place(record, byTurn)) skipped += 1
        }
        val turns = byTurn.map { (id, records) -> TracedTurn(id, records.first, records.second) }
        return TraceRead(turns, skipped)
    }

    /** Files [record] under its turn, or false when this reader has no place for it: no turn id,
     *  or a kind with no branch here. A turn is created ONLY by a kind that puts a record IN it —
     *  a line carrying a turn id under a kind this reader has no branch for (a foreign line, or a
     *  record kind a newer writer has) must be counted and dropped, never left as a turn holding
     *  nothing for TracedTurn.first to read a column off. */
    private fun place(
        record: JsonObject,
        byTurn: MutableMap<String, Pair<MutableList<JsonObject>, JsonObject?>>,
    ): Boolean {
        val id = JsonScalars.str(record, "turn") ?: return false
        val kind = JsonScalars.str(record, "kind")
        if (!placed(kind)) return false
        // The attempt list is shared by reference across the pair rewrite below, so an attempt
        // that lands after the turn record (a late file-lane write) still joins its turn.
        val (attempts, _) = byTurn.getOrPut(id) { mutableListOf<JsonObject>() to null }
        if (kind == TraceKinds.TURN) byTurn[id] = attempts to record else attempts += record
        return true
    }

    private fun placed(kind: String?): Boolean = kind == TraceKinds.ATTEMPT || kind == TraceKinds.TURN

    private fun parse(line: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- V4-174: a torn or foreign line is counted as skipped by the caller and shown to the operator
        Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
