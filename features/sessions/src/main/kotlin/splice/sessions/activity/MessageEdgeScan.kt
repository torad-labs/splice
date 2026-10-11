// NEW: V4-444 — the edges a caller can name, read from the day files and not kept.
//
// WHY THIS EXISTS. The row cache ([MessageEdgeCache]) keeps every edge of every retained day so a poll can ask for any of
// them cheaply, and that is as big as the window: on his desk (36,743 edges over 20 days) it passes its 16 MiB allowance
// and every whole-span caller then answered with the named storage refusal and no edges. A team's board, a session's
// edge list and the all-sessions board each want only the edges that touch THEM. That is a filter over a stream, and
// what the stream holds at any moment is one line.
//
// WHAT IS HELD. Only the edges [EdgeWanted] accepted, and an id for each of them to drop a repeat (the earliest
// observation wins, as in the cache). Nothing is cached between calls, so a second call reads the files again: these
// callers are a person opening a panel, not a poll of every session row.
//
// THE ALLOWANCE STAYS. What is accepted is weighed by the cache's own formula and the call throws the cache's own
// IOException when it would pass [maxBytes], so a runaway writer still cannot take the heap and every caller still
// degrades by name through the refusal it already handles. A caller that names few edges reads a window of any size.
package splice.sessions.activity

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.storage.LineFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Which edges a scan keeps, asked once per decoded row. */
internal fun interface EdgeWanted {
    fun wants(edge: MessageEdge): Boolean
}

// why: map entry, edge object, recipient wrapper and list references beyond separately weighed text.
private const val KEPT_EDGE_BYTES = 256L

// why: the hash entry of an id held to drop a repeat, beyond its characters.
private const val KEPT_ID_BYTES = 64L

// why: transient decoded JSON and UTF-16 text, before only the small edge fields survive.
private const val SCAN_PARSE_EXPANSION = 8L

internal class MessageEdgeScan(
    private val days: ActivityDays,
    private val files: DayFiles,
    private val decode: MessageEdgeDecode,
    private val heap: HeapReservations?,
    private val maxBytes: Long,
) {
    /** The retained edges [wanted] accepts, oldest first, one per tool_use id (the earliest observation wins). With
     *  [since], day files last written before that instant are not read. Throws an IOException when the accepted edges
     *  would pass the allowance. */
    fun scan(wanted: EdgeWanted, since: Long? = null): List<MessageEdge> {
        val found = linkedMapOf<String, MessageEdge>()
        days.retainedFiles().filter { writtenSince(it, since) }.forEach { path ->
            val day = files.open(path) { file -> readDay(file, wanted, found.values.sumOf(::weigh)) }
            day?.values?.toList()?.asReversed()?.forEach { edge -> found.putIfAbsent(edge.id, edge) }
        }
        val result = found.values.toList()
        heap?.let { budget -> val _ = HeapOwners.charge(result, budget, result.sumOf(::weigh)) }
        return result
    }

    /** One day's accepted edges. The lines come newest first, so a repeat read later is an older one and replaces the
     *  one held; the caller reverses the day to oldest first. */
    private fun readDay(file: LineFile, wanted: EdgeWanted, held: Long): Map<String, MessageEdge> {
        val day = linkedMapOf<String, MessageEdge>()
        var weight = held
        val _ = file.lines(0L, file.size) { line ->
            val edge = parse(line.text(), line.byteSize)
            if (edge != null && wanted.wants(edge)) {
                weight += weigh(edge)
                day.remove(edge.id)?.let { weight -= weigh(it) }
                if (weight > maxBytes) {
                    throw IOException("retained message-edge metadata exceeds its $maxBytes byte allowance")
                }
                day[edge.id] = edge
            }
            true
        }
        return day
    }

    // A day file last written before [since] holds no edge at or after it; one that vanished mid-walk is read as empty.
    private fun writtenSince(path: Path, since: Long?): Boolean = since == null || try {
        Files.getLastModifiedTime(path).toMillis() >= since
    } catch (_: NoSuchFileException) {
        false
    }

    private fun parse(text: String, bytes: Long): MessageEdge? {
        val lease = heap?.let { it.reserve(bytes * SCAN_PARSE_EXPANSION) ?: throw HeapCapacityException() }
        return try {
            decode.parse(text)
        } finally {
            lease?.close()
        }
    }

    private fun weigh(edge: MessageEdge): Long = KEPT_EDGE_BYTES + KEPT_ID_BYTES +
        2L * (edge.from.length + edge.to.length + edge.id.length + (edge.toSession?.length ?: 0))
}
