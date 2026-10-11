// NEW: Oct 10, 2026 — deletes the request records written before one moment, which is the half of
// the history control that keeps its promise: the band says "Delete 1,203 turns" and this takes
// exactly those 1,203.
//
// WHY A ROW AND NOT A FILE. PerfStats.sweepArchive already drops archived generations whose LAST
// WRITE is older than the window, which is conservative and right for a background sweep: every row
// in such a file is older than the cutoff, so it can never delete a row the window keeps. What it
// cannot do is cut the generation that STRADDLES the cutoff, and that is the one a person is looking
// at, because the cut moment is the time of day they are reading the page at. A count of rows before
// the moment and a deletion of whole files would be two different numbers wearing one label.
//
// WHY IT RUNS ON THE FILE LANE. The live generation is being appended to by the same lane that owns
// every other write to these files (AsyncFileIo). Rewriting it from any other thread races the
// append: rows written during the rewrite would land in the file the move then replaces. On the lane
// there is nothing to race, and the rewrite itself is a write to a sibling and an atomic move, so a
// concurrent READER sees either the whole old file or the whole new one and never a half-written
// one. That is also what lets a second cut count from what the first left: the moved file has a new
// size and modified time, so HistoryDays rescans it instead of answering from its cached tally.
//
// AN UNDATED ROW IS NEVER TAKEN. A row whose timestamp cannot be read is kept, because splice cannot
// prove it is old, and HistoryDays never counts one into a cut. The count and the deletion agree on
// that, as they must: they read a line through the same RecordLine.
package splice.head.perf

import splice.core.perf.PerfFiles
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// why: rewriting hundreds of megabytes of records can outlast one head's drain budget; the file lane
// still owns the task if this bounded HTTP wait expires, and the next read says what is there now.
private const val PRUNE_WAIT_MS = 120_000L

// why: a sibling of the file being rewritten, so the move is a rename within one directory and
// therefore atomic. The suffix cannot parse as a record name, so a concurrent read never counts it.
private const val PRUNING_SUFFIX = ".tmp"

/** What a prune took: the records deleted, and how the files holding them ended up. */
internal data class HistoryPruned(
    val turns: Long,
    val bytes: Long,
    /** Generations deleted whole, because nothing in them was newer than the moment. */
    val filesDeleted: Int,
    /** Generations rewritten, because they straddled the moment. */
    val filesRewritten: Int,
)

/** Deletes the request records written before a moment, exactly and on the file lane. */
internal class HistoryPrune(private val log: LogSink = LogSink(DaemonLog::write)) {
    private val record = RecordLine()

    /** Delete every dated record written before [momentMs] under [stateDir] and its [archiveDir]. */
    fun before(momentMs: Long, stateDir: Path, archiveDir: Path): HistoryPruned {
        if (!AsyncFileIo.awaitDirectory(stateDir)) stop("pending turn-statistics writes did not settle")
        val ready = CountDownLatch(1)
        val result = AtomicReference<Result<HistoryPruned>?>(null)
        val queued = AsyncFileIo.submit {
            try {
                result.set(Cancellables.runCatchingCancellable { sweep(momentMs, stateDir, archiveDir) })
            } finally {
                ready.countDown()
            }
        }
        if (!queued) stop("the file lane refused the history prune")
        if (!ready.await(PRUNE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            stop("the history prune is still running; read the history again before retrying")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    /** Each of these is a different cause with the same answer: nothing was deleted, and the reason
     *  reaches the person as the refusal the route writes. */
    private fun stop(why: String): Nothing = throw IOException(why)

    private fun sweep(momentMs: Long, stateDir: Path, archiveDir: Path): HistoryPruned {
        var turns = 0L
        var bytes = 0L
        var deleted = 0
        var rewritten = 0
        for (file in records(stateDir) + records(archiveDir)) {
            val taken = take(file, momentMs)
            turns += taken.turns
            bytes += taken.bytes
            if (taken.turns == 0L) continue
            if (taken.wholeFile) deleted += 1 else rewritten += 1
        }
        log("[history] pruned $turns turn(s), $bytes byte(s): $deleted file(s) deleted, $rewritten rewritten\n")
        return HistoryPruned(turns, bytes, deleted, rewritten)
    }

    /** What one file loses to a cut at [momentMs], taken from it before this returns. */
    private fun take(file: Path, momentMs: Long): Taken {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("not a regular turn file")
        }
        val weighed = weigh(file, momentMs)
        when {
            weighed.turns == 0L -> Unit
            weighed.kept == 0L -> Files.deleteIfExists(file)
            else -> rewrite(file, momentMs)
        }
        return Taken(weighed.turns, weighed.bytes, wholeFile = weighed.kept == 0L)
    }

    /** One read that says what a cut takes and whether anything would be left. */
    private fun weigh(file: Path, momentMs: Long): Weighed {
        var turns = 0L
        var bytes = 0L
        var kept = 0L
        RecordLines(file).use { lines ->
            while (true) {
                val line = lines.next() ?: break
                if (keeps(line, momentMs)) {
                    kept += 1
                } else {
                    turns += 1
                    bytes += record.onDisk(line)
                }
            }
        }
        return Weighed(turns, bytes, kept)
    }

    /** The kept rows, written to a sibling and moved over the original in one step. */
    private fun rewrite(file: Path, momentMs: Long) {
        val temp = file.resolveSibling(file.fileName.toString() + PRUNING_SUFFIX)
        Files.deleteIfExists(temp)
        SecureFile.createNew0600(temp, ByteArray(0))
        try {
            copyKept(file, temp, momentMs)
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Every row [momentMs] keeps, in the order it was written, written as it is read. */
    private fun copyKept(from: Path, to: Path, momentMs: Long) {
        Files.newBufferedWriter(to, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { out ->
            RecordLines(from).use { lines -> copyInto(lines, out, momentMs) }
        }
    }

    private fun copyInto(lines: RecordLines, out: Writer, momentMs: Long) {
        while (true) {
            val line = lines.next() ?: break
            if (keeps(line, momentMs)) out.write(line + "\n")
        }
    }

    /** Whether a cut at [momentMs] keeps [line]. The one predicate both passes ask, so the count
     *  this prune reports and the rows it leaves behind can never be two different answers. */
    private fun keeps(line: String, momentMs: Long): Boolean {
        val stamp = record.stampOf(line)
        return stamp == null || stamp >= momentMs
    }

    private fun records(dir: Path): List<Path> = try {
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { PerfFiles.isRecord(it.fileName.toString()) }.toList()
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    private data class Weighed(val turns: Long, val bytes: Long, val kept: Long)
    private data class Taken(val turns: Long, val bytes: Long, val wholeFile: Boolean)
}
