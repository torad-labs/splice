// NEW: V4-444 — what a Sessions listing needs of the message edges is a COUNT PER SESSION, never the edges.
//
// THE BUG THIS ANSWERS. The listing puts `{sent, received, last_at}` on each session row, and it got there by reading
// every retained edge of every day into a map ([MessageEdgeCache]) and counting them per request. That map is as big as
// the window and the day files only grow, so on his desk (20 days, 35,579 edges, about 15 MiB by the cache's own
// formula before the `to_session` field it leaves out) it passed its 16 MiB allowance and `GET /api/sessions` answered
// 500 for nearly three hours on Oct 10. A bigger number only moves the day it happens.
//
// WHAT IS KEPT INSTEAD is the answer the row asks: per day file, how many edges each session SENT, how many each was
// sent by session, and how many went to each address that no session claimed (a legacy edge), each with the time of
// the latest. That is one small entry per distinct sender or recipient, tens to hundreds on his desk, and it does not
// grow with the number of edges. Reading it costs the listing a handful of map lookups per row.
//
// NOTHING ELSE GROWS WITH THE ROWS. An id is held only to drop a repeat inside the file being read, and only for the
// NEWEST file, which is the one that is still being appended to; every older file lets its ids go once it is read, and a
// file that grows after that is simply read again from its start. A repeat of an id across two files is counted twice,
// which on his desk is two edges in 35,585, and it is a tool call recorded twice, not two messages (console-lead,
// Oct 10).
//
// A LINE STILL BEING WRITTEN is not counted until its newline lands: the next poll has it. The row cache counted an
// unterminated last line; a count that is one line late for as long as a writer is mid-line is honest, and the cost of
// counting it would be the bookkeeping that made the old cache large.
//
// THE ALLOWANCE STAYS. [MessageEdgeTotals.totals] throws the same IOException the row cache did when what it holds
// would pass [maxBytes], so a runaway writer still cannot take the heap, and the caller degrades exactly as it does
// for the row cache (ActivityRoutes.read).
package splice.sessions.activity

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.storage.DayLine
import splice.core.storage.LineFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: file identity, cursor, the three maps and the boundary fingerprints.
private const val TOTALS_FILE_BYTES = 768L

// why: a map entry, its key string and its tally.
private const val TOTALS_ENTRY_BYTES = 192L

// why: an id string in the newest file's repeat filter: the hash entry and the text beyond the characters themselves.
private const val TOTALS_ID_OVERHEAD_BYTES = 64L

// why: enough boundary bytes to recognise a changed small row without retaining its JSON.
private const val TOTALS_BOUNDARY_BYTES = 64

// why: transient decoded JSON and UTF-16 text, before only the small edge fields survive.
private const val TOTALS_PARSE_EXPANSION = 8L

/** One session's counts as a row shows them: what it sent, what it was sent, and when the latest of either was. */
public data class EdgeCounts(val sent: Int, val received: Int, val lastAt: Long?)

/** A count and the time of the latest. */
internal class Tally {
    var count: Int = 0
        private set
    var lastAt: Long = Long.MIN_VALUE
        private set

    fun add(at: Long) {
        count++
        if (at > lastAt) lastAt = at
    }

    fun add(other: Tally) {
        count += other.count
        if (other.lastAt > lastAt) lastAt = other.lastAt
    }
}

/**
 * Every retained file's counts, merged, as one snapshot: a listing reads all its rows from this, so two rows cannot
 * straddle a day roll and disagree about the same conversation.
 *
 * The rule a row follows is the one the edge list always followed (EdgeIndex.mine): an edge a session SENT is its
 * `out`, even to itself; an edge stored with the session it reached is that session's `in`; and an edge stored with no
 * session at all is an `in` for the session whose address it names, unless that session sent it.
 */
public class EdgeTotals internal constructor(
    private val sent: Map<String, Tally>,
    private val held: Map<String, Tally>,
    private val legacy: Map<String, Map<String, Tally>>,
) {
    /** [sessionId]'s counts, with [address] where the registry knows one (a legacy edge names an address, not a session). */
    public fun of(sessionId: String, address: String?): EdgeCounts {
        val out = sent[sessionId]
        val received = listOfNotNull(held[sessionId]) + legacyTo(sessionId, address)
        return EdgeCounts(
            sent = out?.count ?: 0,
            received = received.sumOf { it.count },
            lastAt = (received + listOfNotNull(out)).maxOfOrNull { it.lastAt },
        )
    }

    private fun legacyTo(sessionId: String, address: String?): List<Tally> =
        address?.let { legacy[it] }.orEmpty().filterKeys { it != sessionId }.values.toList()
}

internal class MessageEdgeTotals(
    private val days: ActivityDays,
    private val files: DayFiles,
    private val decode: MessageEdgeDecode,
    private val heap: HeapReservations?,
    private val maxBytes: Long,
) {
    private val kept = linkedMapOf<Any, Day>()
    private var answer = EdgeTotals(emptyMap(), emptyMap(), emptyMap())

    @Synchronized
    fun totals(): EdgeTotals {
        val opened = days.retainedFiles().mapNotNull { path -> attributes(path)?.let { path to it } }
        var changed = kept.keys.retainAll(opened.mapTo(HashSet()) { (path, attrs) -> identity(path, attrs) })
        var previous: Day? = null
        val states = opened.mapNotNull { (path, attrs) ->
            files.open(path) { file ->
                val key = identity(path, attrs)
                val before = kept[key]
                // A file that grew after its ids were let go is read again from its start; one still holding them
                // reads only what was added.
                val reusable = before?.takeIf { known(file, it, attrs) }
                val state = if (reusable != null && reusable.canResume(file.size)) {
                    reusable
                } else {
                    Day().also {
                        ensure(TOTALS_FILE_BYTES)
                        kept[key] = it
                    }
                }
                if (state !== before || file.size != state.size) {
                    update(file, state)
                    state.size = file.size
                    state.modified = attrs["lastModifiedTime"] as? FileTime
                    changed = true
                }
                // Only the newest file keeps its ids, so the one before lets them go as soon as this one is open.
                previous?.release()
                previous = state
                state
            }
        }
        if (changed) answer = merge(states)
        return answer
    }

    private fun attributes(path: Path): Map<String, Any>? = try {
        Files.readAttributes(
            path,
            "basic:fileKey,creationTime,lastModifiedTime,isRegularFile",
            LinkOption.NOFOLLOW_LINKS,
        ).takeIf { it["isRegularFile"] == true }
    } catch (_: NoSuchFileException) {
        null
    }

    private fun identity(path: Path, attrs: Map<String, Any>): Any =
        attrs["fileKey"] ?: (path to attrs["creationTime"])

    private fun known(file: LineFile, state: Day, attrs: Map<String, Any>): Boolean {
        if (file.size < state.size) return false
        if (file.size == state.size && attrs["lastModifiedTime"] != state.modified) return false
        return file.bytes(0L, state.first.size).contentEquals(state.first) &&
            file.bytes(state.end - state.last.size, state.last.size).contentEquals(state.last)
    }

    /** Reads what this file gained since [state] last looked, oldest occurrence of an id first in effect: the lines are
     *  walked newest to oldest, so a repeat that is read later is an older one and is the one kept. */
    private fun update(file: LineFile, state: Day) {
        val settled = file.settledEnd()
        val batch = linkedMapOf<String, MessageEdge>()
        var weight = 0L
        val _ = file.lines(state.end, settled) { line ->
            parse(line)?.let { edge ->
                weight += idBytes(edge)
                ensure(weight)
                batch[edge.id] = edge
            }
            true
        }
        val ids = state.ids ?: throw HeapCapacityException()
        batch.values.forEach { edge ->
            if (ids.add(edge.id)) {
                state.idWeight += idBytes(edge)
                state.take(edge)
            }
        }
        ensure(0L)
        state.end = settled
        val count = minOf(settled, TOTALS_BOUNDARY_BYTES.toLong()).toInt()
        state.first = file.bytes(0L, count)
        state.last = file.bytes(settled - count, count)
    }

    private fun parse(line: DayLine): MessageEdge? {
        ensure(line.byteSize * TOTALS_PARSE_EXPANSION)
        val lease = heap?.let { it.reserve(line.byteSize * TOTALS_PARSE_EXPANSION) ?: throw HeapCapacityException() }
        return try {
            decode.parse(line.text())
        } finally {
            lease?.close()
        }
    }

    /** Refuses when what is held plus [extra] would pass the allowance. */
    private fun ensure(extra: Long) {
        if (extra > maxBytes - kept.values.sumOf { it.weight() }) {
            throw IOException("retained message-edge metadata exceeds its $maxBytes byte allowance")
        }
    }

    private fun idBytes(edge: MessageEdge): Long = 2L * edge.id.length + TOTALS_ID_OVERHEAD_BYTES

    private fun merge(states: List<Day>): EdgeTotals {
        val sent = HashMap<String, Tally>()
        val held = HashMap<String, Tally>()
        val legacy = HashMap<String, HashMap<String, Tally>>()
        states.forEach { day ->
            day.sent.forEach { (key, tally) -> sent.getOrPut(key) { Tally() }.add(tally) }
            day.held.forEach { (key, tally) -> held.getOrPut(key) { Tally() }.add(tally) }
            day.legacy.forEach { (to, froms) ->
                val into = legacy.getOrPut(to) { HashMap() }
                froms.forEach { (from, tally) -> into.getOrPut(from) { Tally() }.add(tally) }
            }
        }
        val result = EdgeTotals(sent, held, legacy)
        heap?.let { budget ->
            val entries = sent.size + held.size + legacy.values.sumOf { it.size + 1 }
            val _ = HeapOwners.charge(result, budget, entries * TOTALS_ENTRY_BYTES)
        }
        return result
    }

    /** One day file's counts, and the cursor that says how much of it they cover. */
    private class Day {
        val sent = HashMap<String, Tally>()
        val held = HashMap<String, Tally>()
        val legacy = HashMap<String, HashMap<String, Tally>>()

        /** The ids this file has counted, to drop a repeat; null once the file is no longer the newest. */
        var ids: HashSet<String>? = HashSet()
        var idWeight = 0L
        private var entries = 0L
        var end = 0L
        var size = -1L
        var modified: FileTime? = null
        var first = byteArrayOf()
        var last = byteArrayOf()

        // Whether this state can carry on from its cursor: it still holds its ids, or the file has not grown.
        fun canResume(fileSize: Long): Boolean = ids != null || fileSize == size

        fun weight(): Long = TOTALS_FILE_BYTES + entries * TOTALS_ENTRY_BYTES + idWeight

        fun release() {
            ids = null
            idWeight = 0L
        }

        fun take(edge: MessageEdge) {
            tally(sent, edge.from, edge.at)
            val session = edge.toSession
            when {
                session == null -> tally(legacy.getOrPut(edge.to) { entries++; HashMap() }, edge.from, edge.at)
                session != edge.from -> tally(held, session, edge.at)
            }
        }

        private fun tally(into: MutableMap<String, Tally>, key: String, at: Long) {
            into.getOrPut(key) { entries++; Tally() }.add(at)
        }
    }
}
