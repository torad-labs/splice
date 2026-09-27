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
// The first count after a start reads every byte, 5.6 s on claudex's store on one thread, and an install restarts
// the daemon several times a day; so the files are read on a few lanes at once, each file on one lane with its
// own stamp reader, and what each file counted is added up on the calling thread, which leaves the lanes sharing
// nothing. The lanes are a quarter of the cores at most, and four: the count shares the machine with the turns in
// flight. One count runs at a time: a page that arrives while one runs waits for it and answers with it.
package splice.head.trace

import kotlinx.serialization.json.Json
import splice.core.storage.DayFiles
import splice.core.storage.DayLine
import splice.core.storage.LineFile
import splice.core.storage.LineVisit
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

// why: the bytes a file is known by at each end of what was counted: a trace record's turn id and stamp lie in
// its first hundred bytes, and one page is what the kernel reads to hand over any of them
private const val KNOWN_BYTES = 4096

// why: a lane for every four cores at most: the operator's machine runs at load 11-17, and the turns in flight
// come before a count
private const val CORES_PER_LANE = 4

// why: four files at once bring claudex's first count near the time of its largest file, 536 MB; more lanes
// would take cores from the turns in flight for little
private const val MAX_LANES = 4

/** One store's count, kept between reads; [processors] is how many cores the lanes are a share of. */
internal class TraceCensus(
    private val json: Json,
    private val processors: Int = Runtime.getRuntime().availableProcessors(),
) {
    /** What the store's files held at the last count, each by the first bytes it is known by. */
    @Volatile
    private var kept: Map<String, Counted> = emptyMap()

    /** The count running now, which a page that arrives meanwhile answers with rather than start a second. */
    private val flight = AtomicReference<FutureTask<Count>?>()
    private val threads = Executors.defaultThreadFactory()

    /** How many turns [days] hold over every line of every day, and how many lines placed none: the count that
     *  is running, or a new one when none is. */
    @Throws(IOException::class)
    fun count(days: DayFiles): Count {
        val mine = FutureTask { countNow(days) }
        val running = flight.compareAndExchange(null, mine) ?: mine.also {
            it.run()
            flight.set(null)
        }
        return answer(running)
    }

    private fun countNow(days: DayFiles): Count {
        val before = kept
        val files = days.files()
        val lanes = minOf(files.size, processors / CORES_PER_LANE, MAX_LANES)
        val reads = files.map { file -> Callable { days.open(file) { counted(it, before, TraceStamps(json)) } } }
        val counts = (if (lanes < 2) reads.map { it.call() } else onLanes(lanes, reads)).filterNotNull()
        kept = counts.mapNotNull { it.kept }.associateBy { it.first }
        val placed = counts.flatMapTo(HashSet()) { it.placed }
        return Count(placed.size, counts.sumOf { it.skipped })
    }

    /** What each of [reads] answered, run on [lanes] threads at once, which end with the call. */
    private fun <T> onLanes(lanes: Int, reads: List<Callable<T>>): List<T> {
        val pool = Executors.newFixedThreadPool(lanes) { task ->
            threads.newThread(task).apply {
                name = "trace-count"
                isDaemon = true
            }
        }
        return try {
            pool.invokeAll(reads).map { answer(it) }
        } finally {
            pool.shutdown()
        }
    }

    /** What [task] answered, or the failure it ended in, as it was thrown. */
    private fun <T> answer(task: Future<T>): T =
        try {
            task.get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
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
