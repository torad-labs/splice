// NEW: V4-174 — the read side of a head's trace files, for `splice trace`: the lines of every
// retained day, parsed and grouped by turn, oldest first. Reads the SAME day files the daemon's
// TraceStore writes (StatePaths.traceDir, `<head>-YYYY-MM-DD.jsonl`) through ActivityDays' own
// DayFiles, so the CLI needs no daemon — the perf/logs idiom — and can never disagree with the
// writer about where a head's trace lives. A line this reader cannot place is counted, not fatal:
// a torn append heals on the next write (JsonlSink) and the operator is told how many were skipped.
//
// V4-338: read from the NEWEST line back, one line at a time, holding only the records of the turns it
// answers with. It held every record of every day before taking the last N, and a record carries the
// whole conversation: on claudex's 3.7 GB of 2-3 MB lines (2026-09-26) `splice trace --last 3 --json`
// died with an OutOfMemoryError, and the console's trace page would have taken the daemon with it. The
// newest turns are the ones whose LATEST record is newest: every record of a turn lies at or before its
// latest, so a read from the end meets its turns in that order and holds each one until its opening
// record (its first attempt, or a turn record that made none) is read, and a turn whose first attempt
// came with no turn record yet through the minute before it, where a late-written turn record would lie.
// The read itself is [TraceTail].
//
// V4-343: the count of the turns on disk is [TraceCensus], kept for as long as this reader lives, which for the
// console's route is the daemon's life: a page counts only what the day files gained since the last one, where
// every page read every line of every day. The turns are read first, so every turn listed is in the count.
package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.perf.PerfKeys
import splice.core.storage.DayFiles
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
    val model: String get() = JsonScalars.strOrEmpty(first["model"])
    val compact: Boolean get() = JsonScalars.str(first, "compact") == "true"

    /** How the turn ended, off its turn record; null while it is open. The verb's table and the
     *  console's list read the same columns from here (V4-239). */
    val ending: TurnEnding? get() = turn?.let { record ->
        val marks = record["perf"]?.jsonObject?.get("marks")?.jsonObject
        TurnEnding(
            outcome = JsonScalars.strOrEmpty(record["outcome"]),
            rounds = JsonScalars.strOrEmpty(record["rounds"]),
            attempts = JsonScalars.strOrEmpty(record["attempts"]),
            totalMs = marks?.let { JsonScalars.str(it, PerfKeys.TOTAL) },
        )
    }
}

/** A turn record's closing columns, as the record wrote them; [totalMs] is null when its perf carried
 *  no total. */
internal data class TurnEnding(val outcome: String, val rounds: String, val attempts: String, val totalMs: String?)

/** What a reader asked for: the newest [last] turns, of the sessions starting with [session] when one is
 *  given, or the one turn whose id is [turn]. */
internal data class TraceAsk(val last: Int, val session: String? = null, val turn: String? = null) {
    /** How many turns answer it: one, for a turn id. */
    val wanted: Int get() = if (turn != null) 1 else last

    fun admits(id: String, sessionId: String?): Boolean =
        (turn == null || id == turn) && (session == null || sessionId?.startsWith(session) == true)
}

/** The turns asked for, oldest first, and the store they came from: [onDisk] turns on disk and
 *  [skippedLines] lines no turn placed, over every line of every day. */
internal data class TraceRead(val turns: List<TracedTurn>, val onDisk: Int, val skippedLines: Int)

internal class TraceRows(private val json: Json = Json { ignoreUnknownKeys = true }) {
    /** Each store's count, by its trace dir and head, kept for as long as this reader lives. */
    private val censuses = ConcurrentHashMap<Pair<Path, String>, TraceCensus>()

    /** The head's day files, everything on disk (retention is the daemon's business, V4-273). */
    internal fun days(traceDir: Path, head: String): DayFiles = DayFiles(traceDir, head)

    /** The turns [ask] names, oldest first, with how many turns and skipped lines the store holds over every
     *  line of every day; the count reads only what the files gained since this reader last counted them. A
     *  trace dir or a day that cannot be read throws why (V4-286), so no turns means none on disk. */
    @Throws(IOException::class)
    internal fun read(traceDir: Path, head: String, ask: TraceAsk): TraceRead {
        val turns = turns(traceDir, head, ask)
        val count = censuses.computeIfAbsent(traceDir to head) { TraceCensus(json) }.count(days(traceDir, head))
        return TraceRead(turns, count.onDisk, count.skippedLines)
    }

    /** The turns [ask] names, oldest first, read from the newest line only until each is whole. */
    @Throws(IOException::class)
    internal fun turns(traceDir: Path, head: String, ask: TraceAsk): List<TracedTurn> {
        val tail = TraceTail(ask, json)
        days(traceDir, head).newestFirst(tail)
        return tail.turns()
    }
}
