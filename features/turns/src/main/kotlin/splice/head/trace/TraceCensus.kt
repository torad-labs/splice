// NEW: V4-343 — the count of a head's trace kept between reads: how many turns are on disk and how many lines no
// turn placed, the two numbers the console's trace page shows over its list. Counting every line of every day
// for each page took 3.5-4.9 s on claudex's 4 GB store even with each stamp read off its bytes, and it grew with
// the store; a count now reads what the files gained since the last one.
//
// A day file only grows: JsonlSink appends, heals a torn tail by appending a newline, and rotates by moving the
// file over its rolled half (JsonlSink.appendLine). So a file's lines before its settled end (LineFile.settledEnd)
// are final, and what they placed and skipped is kept, with the file's first bytes and the last ones counted. A
// file that opens with the first bytes of a file counted before, and still holds the bytes that count ended on,
// is that file under whatever name it has now, a rotated day's rolled half among them: only the lines after that
// end are read. Any other file is counted from its start. The lines after the settled end, a torn tail or a \r
// that a \n may yet join, are counted on every read and never kept.
//
// No two trace files open with the same bytes, since a record opens with its turn's id and its stamp; and the
// last bytes counted stand for those between them and the first, which no writer rewrites (a byte changed in
// place there is not read again, TraceCensusIncrementalTest).
//
// Two pages at once each count from what was kept when they began, and each keeps what it counted. Neither holds
// a lock across the files it reads, and each answer is exact.
package splice.head.trace

import kotlinx.serialization.json.Json
import splice.core.storage.DayFiles
import splice.core.storage.DayLine
import splice.core.storage.LineFile
import splice.core.storage.LineVisit
import java.io.IOException

// why: the bytes a file is known by at each end of what was counted: a trace record's turn id and stamp lie in
// its first hundred bytes, and one page is what the kernel reads to hand over any of them
private const val KNOWN_BYTES = 4096

/** One store's count, kept between reads. */
internal class TraceCensus(private val json: Json) {
    /** What the store's files held at the last count, each by the first bytes it is known by. */
    @Volatile
    private var kept: Map<String, Counted> = emptyMap()

    /** How many turns [days] hold over every line of every day, and how many lines placed none. */
    @Throws(IOException::class)
    fun count(days: DayFiles): Count {
        val before = kept
        val stamps = TraceStamps(json)
        val files = days.eachFile { file -> counted(file, before, stamps) }
        kept = files.mapNotNull { it.kept }.associateBy { it.first }
        val placed = files.flatMapTo(HashSet()) { it.placed }
        return Count(placed.size, files.sumOf { it.skipped })
    }

    /** [file]'s lines counted: the ones a count before read, as it kept them, then the ones after. */
    private fun counted(file: LineFile, before: Map<String, Counted>, stamps: TraceStamps): FileCount {
        val settled = file.settledEnd()
        val first = text(file.bytes(0L, KNOWN_BYTES))
        val known = before[first]?.takeIf { it.end <= settled && it.last == lastBefore(file, it.end) }
        val whole = Tally(stamps, known)
        val _ = file.lines(known?.end ?: 0L, settled, whole)
        val torn = Tally(stamps, null)
        val _ = file.lines(settled, file.size, torn)
        val keep = if (settled < KNOWN_BYTES) null else Counted(settled, first, lastBefore(file, settled), whole)
        return FileCount(keep, whole.placed + torn.placed, whole.skipped + torn.skipped)
    }

    /** The bytes that end at [end], which is at least [KNOWN_BYTES]. */
    private fun lastBefore(file: LineFile, end: Long): String = text(file.bytes(end - KNOWN_BYTES, KNOWN_BYTES))

    /** Bytes as a String of one char each, so two compare, and key a map, by their bytes. */
    private fun text(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /** The turns on disk and the lines no turn placed. */
    internal data class Count(val onDisk: Int, val skippedLines: Int)

    /** A file's whole lines before [end]: the turn ids they placed, how many placed none, and the file's [first]
     *  bytes and its [last] ones before [end], by which it is known again. */
    private class Counted(val end: Long, val first: String, val last: String, tally: Tally) {
        val placed: Set<String> = tally.placed
        val skipped: Int = tally.skipped
    }

    /** One file's part of a count: what to keep of it, and every turn id it placed and line it skipped. */
    private data class FileCount(val kept: Counted?, val placed: Set<String>, val skipped: Int)

    /** Lines tallied from what [from] counted: the turn ids they placed and how many placed none. */
    private class Tally(private val stamps: TraceStamps, from: Counted?) : LineVisit {
        val placed = HashSet(from?.placed.orEmpty())
        var skipped = from?.skipped ?: 0

        override fun line(line: DayLine): Boolean {
            val id = stamps.of(line)?.placedId
            if (id == null) skipped += 1 else placed += id
            return true
        }
    }
}
