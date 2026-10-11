// NEW: Oct 10, 2026 — what the history control reads: the turns and the BYTES splice holds, by day
// for the bar and to the minute for the count, so a person sees the shape of their own history and
// exactly what a shorter window would delete before it deletes anything (Settings > Your data).
//
// BYTES ARE THE FILE'S OWN BYTES, not a request size. The question under the bar is "how much disk
// do I get back", so a day is charged the bytes its records occupy: the length of every line written
// in it, which is what deleting them frees. The hourly totals' req_bytes is a different number (what
// clients sent) and would draw a bar that disagrees with the space the person actually gets.
//
// WHY MINUTES AND NOT DAYS. The band under the picker has to cut where HistoryWindow.cutoffMs cuts
// (hitstop), and that moment is the time of day the person is looking at it: a 7-day window read at
// 3:12 PM cuts mid-day, so a per-day tally would promise a whole day splice is going to keep most
// of. Every record is therefore charged to its MINUTE, and a cut is answered by binary search over
// the minutes held. The count is exact for any minute-aligned moment, which is every moment this
// wire offers, and a minute of records costs ~24 bytes of tally rather than a stamp per row.
//
// THE SCAN IS CACHED BY FILE IDENTITY. Records run to 64 MB a generation and a person may hold
// hundreds of megabytes, so a scan per page poll is not an option. An archived generation never
// changes, so its tally is read once and kept against its (size, modified) pair; the live generation
// is re-scanned only when it has grown. A file that is pruned or rotated changes both, so the next
// read sees what is there now, which is also what makes a second cut count from what the first left.
//
// EVERY LINE IS CHARGED, including one whose timestamp cannot be read. A row splice cannot place in
// a minute still occupies the disk, so its bytes are in the total and its turn is reported apart
// (unknownTurns). It is never counted into a cut: splice does not promise to delete a row it could
// not date. A figure that silently dropped those rows would tell a person they free less than they
// do, and one that cut them would delete more than the number that was on the screen.
package splice.head.perf

import splice.core.perf.PerfFiles
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

// why: the stamp every record carries, found without parsing the object around it. A 385 MB scan
// that reads one number per line is a fraction of the same scan through a JSON parser.
private const val STAMP_KEY = "\"ts\":"
private const val LOCAL_STEP_KEY = "\"${PerfKeys.LOCAL_STEP}\":1"

// why: a record is charged to the minute it was written in; the header says why not the day.
private val RECORD_MINUTE_MS = 1.minutes.inWholeMilliseconds

/** One day of held records: when it starts, how many turns it holds, and the bytes they occupy. */
internal data class HistoryDay(val startMs: Long, val turns: Long, val bytes: Long)

/** What a window cutting at [cutoffMs] would delete. */
internal data class HistoryCut(val cutoffMs: Long, val turns: Long, val bytes: Long)

/**
 * What splice holds right now, as the history control reads it.
 *
 * Not a data class on purpose: it carries the minute tally as primitive arrays, and generated
 * equality over arrays compares identities, which would read as "two different histories" for one.
 */
internal class HistoryHeld(
    /** Oldest first, and only days that hold something: a day splice wrote nothing in is no bar. */
    val days: List<HistoryDay>,
    val turns: Long,
    val bytes: Long,
    /** Turns on disk with no readable timestamp. Counted in [turns], never in a cut. */
    val unknownTurns: Long,
    /** The oldest record's own minute, which is where the bar begins and what "Since" names. */
    val oldestMs: Long?,
    /** Why this reading is partial, when it is. A partial reading never authorizes a deletion. */
    val readError: String?,
    private val minutes: MinuteTally,
) {
    /** The turns and bytes splice would delete by cutting at [momentMs]. Exact to the minute. */
    fun before(momentMs: Long): HistoryCut = minutes.before(momentMs)

    /** The bytes written since [momentMs], which is what a monthly rate is measured over. */
    fun bytesSince(momentMs: Long): Long = minutes.bytesSince(momentMs)
}

/** Reads the request records one install holds: by day for the bar, by minute for the cut. */
public class HistoryDays(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val cached = HashMap<String, MinuteTally>()
    private val record = RecordLine()

    /** Everything held under [stateDir] and its [archiveDir]. */
    internal fun held(stateDir: Path, archiveDir: Path): HistoryHeld {
        val failures = ArrayList<String>()
        val files = records(stateDir, failures) + records(archiveDir, failures)
        val merged = MinuteBuilder()
        files.forEach { file -> tally(file, failures).into(merged) }
        val minutes = merged.build()
        return HistoryHeld(
            days = minutes.days(zone),
            turns = minutes.turns + minutes.unknownTurns,
            bytes = minutes.bytes + minutes.unknownBytes,
            unknownTurns = minutes.unknownTurns,
            oldestMs = minutes.oldestMs,
            readError = failures.firstOrNull(),
            minutes = minutes,
        )
    }

    private fun records(dir: Path, failures: MutableList<String>): List<Path> = Cancellables
        .runCatchingCancellable {
            if (Files.isSymbolicLink(dir)) throw IOException("the turn-statistics directory is a symlink")
            Files.newDirectoryStream(dir).use { entries ->
                entries.filter { PerfFiles.isRecord(it.fileName.toString()) }.toList()
            }
        }
        .onFailure { failure ->
            if (failure !is NoSuchFileException) failures += SafeFailureText.render(failure)
        }
        .getOrDefault(emptyList())

    /** One file's minutes, scanned once per (path, size, modified) and kept against it. */
    private fun tally(file: Path, failures: MutableList<String>): MinuteTally = Cancellables
        .runCatchingCancellable {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("not a regular turn file")
            }
            val identity = "$file\u0000${Files.size(file)}\u0000${Files.getLastModifiedTime(file).toMillis()}"
            cached[identity] ?: scan(file).also {
                cached.keys.removeIf { seen -> seen.substringBefore('\u0000') == "$file" }
                cached[identity] = it
            }
        }
        .onFailure { failures += "${file.fileName}: ${SafeFailureText.render(it)}" }
        .getOrDefault(EMPTY_MINUTES)

    private fun scan(file: Path): MinuteTally {
        val minutes = MinuteBuilder()
        RecordLines(file).use { lines ->
            while (true) {
                val line = lines.next() ?: break
                // A local step is no request: it takes disk, so a cut still frees it, but it is no row of the count.
                minutes.charge(record.minuteOf(line), record.onDisk(line), if (record.isLocalStep(line)) 0 else 1)
            }
        }
        return minutes.build()
    }
}

/** A record line read the cheap way, by the two readers that must agree on what a line IS: the
 *  tally that counts what a cut would take, and the prune that takes it. A stamp read one way here
 *  and another way there is a count that promises what the deletion does not do. */
internal class RecordLine {
    /** The millisecond a record was written at, or null when its stamp is not there to read. */
    fun stampOf(line: String): Long? {
        val at = line.indexOf(STAMP_KEY).takeIf { it >= 0 } ?: return null
        return line.drop(at + STAMP_KEY.length).takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** The minute a record belongs to, which is the grain a cut is counted at. */
    fun minuteOf(line: String): Long? = stampOf(line)?.let { it / RECORD_MINUTE_MS * RECORD_MINUTE_MS }

    /** Whether a record is a step splice answered itself, which the Requests page leaves out and no count of requests
     *  may include. */
    fun isLocalStep(line: String): Boolean {
        val at = line.indexOf(LOCAL_STEP_KEY).takeIf { it >= 0 } ?: return false
        return line.getOrNull(at + LOCAL_STEP_KEY.length)?.isDigit() != true
    }

    /** The bytes a line occupies, with the newline the reader stripped charged back to it. */
    fun onDisk(line: String): Long = line.toByteArray(Charsets.UTF_8).size + 1L

    /** [ms] on the grain this tally counts at, which is the grain a cut may be offered on: a moment
     *  between two minutes would promise part of a minute the count cannot answer for. */
    fun flooredToMinute(ms: Long): Long = ms / RECORD_MINUTE_MS * RECORD_MINUTE_MS
}

/**
 * The lines of one record file, decoded so a torn byte cannot throw mid-scan, and pulled a line at a
 * time rather than handed a lambda so a caller that writes as it reads is not inside someone else's
 * loop. The handle is this object's, so every caller opens it with `use`.
 */
internal class RecordLines(file: Path) : Closeable {
    private val reader = InputStreamReader(
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE),
    ).buffered()

    /** The next line, or null once the file is read. */
    fun next(): String? = reader.readLine()

    override fun close() = reader.close()
}

// why: no records at all, which is what a file splice could not open contributes to a reading.
private val EMPTY_MINUTES: MinuteTally = MinuteTally(LongArray(0), LongArray(1), LongArray(1), 0, 0)

/** Turns and bytes per minute, sorted, with the running sums a cut is answered from. */
internal class MinuteTally(
    /** Minute starts, strictly increasing. */
    private val at: LongArray,
    /** Turns in `at[0 until i]`, so the last entry is every turn held. One longer than [at]. */
    private val turnsUpTo: LongArray,
    /** Bytes in `at[0 until i]`, the same shape as [turnsUpTo]. */
    private val bytesUpTo: LongArray,
    val unknownTurns: Long,
    val unknownBytes: Long,
) {
    val turns: Long get() = turnsUpTo[at.size]
    val bytes: Long get() = bytesUpTo[at.size]
    val oldestMs: Long? get() = at.firstOrNull()

    fun before(momentMs: Long): HistoryCut {
        val end = minutesBefore(momentMs)
        return HistoryCut(momentMs, turnsUpTo[end], bytesUpTo[end])
    }

    fun bytesSince(momentMs: Long): Long = bytes - bytesUpTo[minutesBefore(momentMs)]

    /** Oldest first, every minute folded into the day it falls in where the daemon runs. */
    fun days(zone: ZoneId): List<HistoryDay> {
        val out = ArrayList<HistoryDay>()
        for (index in at.indices) {
            val start = Instant.ofEpochMilli(at[index]).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val turns = turnsUpTo[index + 1] - turnsUpTo[index]
            val bytes = bytesUpTo[index + 1] - bytesUpTo[index]
            val last = out.lastOrNull()
            if (last != null && last.startMs == start) {
                out[out.size - 1] = last.copy(turns = last.turns + turns, bytes = last.bytes + bytes)
            } else {
                out += HistoryDay(start, turns, bytes)
            }
        }
        return out
    }

    /** Fold this file's minutes into a reading of every file. */
    fun into(builder: MinuteBuilder) {
        for (index in at.indices) {
            builder.charge(
                at[index],
                bytesUpTo[index + 1] - bytesUpTo[index],
                turnsUpTo[index + 1] - turnsUpTo[index],
            )
        }
        builder.chargeUndated(unknownTurns, unknownBytes)
    }

    /** How many minutes start strictly before [momentMs]: what a cut at that moment takes. */
    private fun minutesBefore(momentMs: Long): Int {
        var low = 0
        var high = at.size
        while (low < high) {
            val mid = (low + high) / 2
            if (at[mid] < momentMs) low = mid + 1 else high = mid
        }
        return low
    }
}

/** Collects charges in any order and sorts once, because files are read in directory order. */
internal class MinuteBuilder {
    private val turns = HashMap<Long, Long>()
    private val bytes = HashMap<Long, Long>()
    private var undatedTurns = 0L
    private var undatedBytes = 0L

    fun charge(minute: Long?, onDisk: Long, rows: Long = 1) {
        if (minute == null) {
            chargeUndated(rows, onDisk)
            return
        }
        turns[minute] = (turns[minute] ?: 0) + rows
        bytes[minute] = (bytes[minute] ?: 0) + onDisk
    }

    fun chargeUndated(rows: Long, onDisk: Long) {
        undatedTurns += rows
        undatedBytes += onDisk
    }

    fun build(): MinuteTally {
        val at = turns.keys.toLongArray().also { it.sort() }
        val turnsUpTo = LongArray(at.size + 1)
        val bytesUpTo = LongArray(at.size + 1)
        for (index in at.indices) {
            turnsUpTo[index + 1] = turnsUpTo[index] + (turns[at[index]] ?: 0)
            bytesUpTo[index + 1] = bytesUpTo[index] + (bytes[at[index]] ?: 0)
        }
        return MinuteTally(at, turnsUpTo, bytesUpTo, undatedTurns, undatedBytes)
    }
}
