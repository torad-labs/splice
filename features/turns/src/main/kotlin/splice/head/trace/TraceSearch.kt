// NEW: Oct 10, 2026 — the search over what was sent and what came back (BUILD.md, "Requests: search what was sent
// and what came back"). One head's trace, newest line first, until it has [TraceSearchAsk.limit] hits, has read back
// to [TraceSearchAsk.since], or has spent its time: each ended turn's client body (what Claude Code sent splice) and
// answer body (what splice handed back) are read ONE TURN AT A TIME, searched, and let go, so a month of 2-3 MB
// conversations is never held.
//
// IT SAYS HOW FAR IT GOT. A search that stops on its time or its limit has not looked at the older turns, and an empty
// answer from it is not "nothing was ever sent": [TraceFound.backTo] is the time of the oldest turn it read and
// [TraceFound.stoppedOn] says why it stopped, so the page can say "searched back to 3:14 PM" and never draw the
// older silence as an absence.
//
// A TURN IS ONE HIT, however many times its bodies carry the words. A conversation is sent whole with every request, so
// a prompt typed once is in the body of every later request of its session; a hit on "sent" shows the LAST place the
// words stand, the newest part of the conversation, which is the part this request added.
//
// A HIT CARRIES ITS TURN'S OWN ENDING ([TraceEnding]): a search reaches turns the perf window has long since cut, and a
// hit the page could not draw is a hit the page would have to drop.
package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.storage.DayLine
import splice.core.storage.LineVisit
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.head.trace.body.TraceBodies
import splice.head.trace.body.TraceBodyReaders
import splice.head.trace.body.TraceBodySelection
import splice.head.trace.body.TraceChunkDecoder
import splice.head.trace.body.TracePackFormat
import splice.upstream.memory.JvmHeap

// why: the words either side of a match that make the snippet readable on one line of the list
private const val SNIPPET_AROUND_CHARS = 36

// why: a request that answers in seconds must not hold a page open for a month of conversations; the page asks
// again for the older ones from where this stopped
internal const val TRACE_SEARCH_BUDGET_MS: Long = 12_000L

// why: the shortest text that is a search and not a letter; one character matches every body
internal const val TRACE_SEARCH_MIN_CHARS: Int = 2

// why: a page of a list is a few dozen requests, and this is how many a search answers with unless it is asked for more
internal const val SEARCH_DEFAULT_LIMIT: Long = 25L

// why: a hundred is the most one search is worth reading, and each hit costs a body read
internal const val SEARCH_MAX_LIMIT: Long = 100L

internal const val SEARCH_TOO_SHORT = "q must be at least 2 characters"
internal const val SEARCH_BAD_SINCE = "since must be a non-negative epoch-ms instant"
internal const val SEARCH_BAD_LIMIT = "limit must be a whole number above zero"

/** What one search asked of the store, once its parameters are read. */
internal data class TraceSearchAsk(val needle: String, val since: Long, val limit: Int)

/** A search's parameters, or the sentence naming the one it could not read. */
internal sealed class TraceSearchRead {
    data class Read(val ask: TraceSearchAsk) : TraceSearchRead()

    data class Refused(val message: String) : TraceSearchRead()
}

/** Reads the parameters of one search. A value spelled wrong is REFUSED by name, never replaced by its default. */
internal class TraceSearchReader {
    fun read(query: TraceSearchQuery): TraceSearchRead {
        val needle = query.q?.trim().orEmpty()
        val since = whole(query.since, 0L, 0L)
        val limit = whole(query.limit, SEARCH_DEFAULT_LIMIT, 1L)
        return when {
            needle.length < TRACE_SEARCH_MIN_CHARS -> TraceSearchRead.Refused(SEARCH_TOO_SHORT)
            since == null -> TraceSearchRead.Refused(SEARCH_BAD_SINCE)
            limit == null -> TraceSearchRead.Refused(SEARCH_BAD_LIMIT)
            else -> TraceSearchRead.Read(TraceSearchAsk(needle, since, limit.coerceAtMost(SEARCH_MAX_LIMIT).toInt()))
        }
    }

    /** [text] as a whole number at least [min], [default] when it is blank, null when it is neither. */
    private fun whole(text: String?, default: Long, min: Long): Long? =
        if (text.isNullOrBlank()) default else text.toLongOrNull()?.takeIf { it >= min }
}

/** Which of a turn's two bodies a hit is in. */
internal enum class TraceSide(val wire: String) { SENT("sent"), RETURNED("returned") }

/** What a turn's own ending records, enough to draw its row without the perf file. */
internal data class TraceEnding(
    val outcome: String?,
    val model: String?,
    val compact: Boolean,
    val attempts: Long?,
    val counters: Map<String, Long>,
    val marks: Map<String, Long>,
)

/** One turn whose body holds the words, with the stretch around them and what its ending recorded. */
internal data class TraceHit(
    val turn: String,
    val at: Long,
    val session: String?,
    val side: TraceSide,
    val text: String,
    val ending: TraceEnding,
)

/** Why a search stopped before the oldest line of the store. */
internal enum class TraceStop(val wire: String) { LIMIT("limit"), TIME("time") }

/** The hits, newest first, and how far back the search read: [backTo] is the oldest ended turn it opened, null when
 *  it opened none, and [stoppedOn] is why it stopped early, null when it read back to the window's start. */
internal data class TraceFound(
    val hits: List<TraceHit>,
    val turnsRead: Int,
    val backTo: Long?,
    val stoppedOn: TraceStop?,
)

/** A turn record placed by its stamp: the turn it ends and when it was written. */
private data class Placed(val id: String, val at: Long, val stamp: TraceStamp)

internal class TraceSearch(
    private val ask: TraceSearchAsk,
    private val clock: WallClock,
    private val json: Json,
    private val heap: HeapReservations = JvmHeap.budget,
    decoder: TraceChunkDecoder = TraceChunkDecoder(TracePackFormat::decode),
) : LineVisit, AutoCloseable {
    private val started = clock()
    private val stamps = TraceStamps(json, heap)
    private val bodies = TraceBodies(heap = heap, decoder = decoder)
    private val readers = TraceBodyReaders(heap)
    private val whitespace = Regex("""\s+""")
    private val hits = ArrayList<TraceHit>()
    private var read = 0
    private var oldest: Long? = null
    private var stop: TraceStop? = null

    override fun close() = readers.close()

    fun found(): TraceFound = TraceFound(hits, read, oldest, stop)

    /** Newest line first: the first turn record older than the window is the end of what was asked for, and a line that
     *  is no turn record is passed over. Goes on while neither the limit nor the time is spent. */
    override fun line(line: DayLine): Boolean {
        stop = spent()
        val turn = if (stop == null) placed(line) else null
        val inside = inside(turn)
        if (turn != null && inside) take(line, turn)
        return stop == null && inside
    }

    /** A line that is no turn record is passed over, so it keeps the read going; a turn older than the window is the
     *  end of what was asked for. */
    private fun inside(turn: Placed?): Boolean = turn == null || turn.at >= ask.since

    private fun spent(): TraceStop? = when {
        hits.size >= ask.limit -> TraceStop.LIMIT
        clock() - started >= TRACE_SEARCH_BUDGET_MS -> TraceStop.TIME
        else -> null
    }

    /** The ended turn this line records, placed by its stamp; null when the line is no ended turn's record. */
    private fun placed(line: DayLine): Placed? {
        val stamp = stamps.of(line)?.takeIf { it.isTurnRecord } ?: return null
        val at = stamp.at ?: return null
        return stamp.placedId?.let { Placed(it, at, stamp) }
    }

    private fun take(line: DayLine, turn: Placed) {
        read++
        oldest = turn.at
        hit(line, turn)?.let { hits += it }
    }

    private fun hit(line: DayLine, turn: Placed): TraceHit? {
        if (line.byteSize >= Int.MAX_VALUE) throw HeapCapacityException()
        return line.bytes().use { input ->
            HeapText.Reader.read(input, line.byteSize, heap).use { staged ->
                val record = json.parseToJsonElement(staged.text).jsonObject
                val selected = bodies.selected(record, line.file, readers, TraceBodySelection.RECORDS)
                val sent = around(selected, "client", true)?.let { TraceSide.SENT to it }
                val found = sent ?: around(selected, "answer", false)?.let { TraceSide.RETURNED to it }
                found?.let { (side, text) ->
                    TraceHit(turn.id, turn.at, JsonScalars.str(turn.stamp.session), side, text, endingOf(selected))
                }
            }
        }
    }

    private fun endingOf(record: JsonObject): TraceEnding {
        val perf = record["perf"] as? JsonObject
        return TraceEnding(
            outcome = JsonScalars.str(record["outcome"]),
            model = JsonScalars.str(record["model"]),
            compact = JsonScalars.str(record["compact"]) == "true",
            attempts = JsonScalars.long(record, "attempts"),
            counters = numbers(perf?.get("counters")),
            marks = numbers(perf?.get("marks")),
        )
    }

    private fun numbers(block: JsonElement?): Map<String, Long> {
        val obj = block as? JsonObject ?: return emptyMap()
        return buildMap { obj.keys.forEach { name -> JsonScalars.long(obj, name)?.let { put(name, it) } } }
    }

    /** The words around the match in the body of [section], on one line: its newest place when [last] (the part of a
     *  conversation a request added), its first otherwise. Null when the body is not here or does not hold them. */
    private fun around(record: JsonObject, section: String, last: Boolean): String? {
        val text = JsonScalars.str((record[section] as? JsonObject)?.get("body")) ?: return null
        val needle = ask.needle
        val at = if (last) text.lastIndexOf(needle, ignoreCase = true) else text.indexOf(needle, ignoreCase = true)
        if (at < 0) return null
        val from = (at - SNIPPET_AROUND_CHARS).coerceAtLeast(0)
        val to = (at + needle.length + SNIPPET_AROUND_CHARS).coerceAtMost(text.length)
        return text.substring(from, to).replace(whitespace, " ")
    }
}
