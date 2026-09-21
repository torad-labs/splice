// NEW: V4-174 — the read side of a head's trace files, for `splice trace`: the lines of every
// retained day, parsed and grouped by turn, oldest first. Reads the SAME day files the daemon's
// TraceStore writes (StatePaths.traceDir, `<head>-YYYY-MM-DD.jsonl`) through the same ActivityDays,
// so the CLI needs no daemon — the perf/logs idiom — and can never disagree with the writer about
// where a head's trace lives. A line that is not JSON is counted, not fatal: a torn append heals on
// the next write (JsonlSink) and the operator is told how many lines were skipped.
package splice.app.cli.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.activity.ActivityDays
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.head.wire.TraceKinds
import java.nio.file.Path

/** Every record of one turn, in the order they were written: the attempts, then the turn record
 *  (null when the turn has not ended yet, or its ending was lost to the file lane). */
internal data class TracedTurn(val id: String, val attempts: List<JsonObject>, val turn: JsonObject?) {
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
            val id = record?.let { JsonScalars.str(it, "turn") }
            if (record == null || id == null) {
                skipped += 1
                continue
            }
            // The attempt list is shared by reference across the pair rewrite below, so an attempt
            // that lands after the turn record (a late file-lane write) still joins its turn.
            val (attempts, _) = byTurn.getOrPut(id) { mutableListOf<JsonObject>() to null }
            when (JsonScalars.str(record, "kind")) {
                TraceKinds.ATTEMPT -> attempts += record
                TraceKinds.TURN -> byTurn[id] = attempts to record
                else -> skipped += 1
            }
        }
        val turns = byTurn.map { (id, records) -> TracedTurn(id, records.first, records.second) }
        return TraceRead(turns, skipped)
    }

    private fun parse(line: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- V4-174: a torn or foreign line is counted as skipped by the caller and shown to the operator
        Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
