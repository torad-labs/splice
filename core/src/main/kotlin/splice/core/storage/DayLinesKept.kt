// NEW: Oct 10, 2026 — dropping SOME of a day store's lines, at a moment the person was shown.
//
// WHY A WHOLE-FILE DELETE DOES NOT ANSWER IT. A day file holds a UTC day, and the history window is
// cut at the person's own midnight: in Chicago that is 05:00 UTC, which falls INSIDE the UTC day
// that began at 19:00 the evening before. Deleting only the days that END before the cut therefore
// keeps every edge from that evening, so someone who chose "Today only" would still read yesterday
// 7 pm's messages on Teams. Marlin, Oct 10: when a person says yes to a deletion, everything the
// history covers is gone at that moment. So the straddling day is rewritten line by line.
//
// THE SAME LANE AND THE SAME LOCK AS THE APPENDS. The rewrite runs on AsyncFileIo under the store's
// own day lock, so no append lands in a file being rewritten and no reader sees a half-written day.
// Each rewrite goes to a 0600 sibling and then ONE ATOMIC_MOVE over the day, which is how every
// other record file in splice is replaced; a day with nothing left is deleted outright, and a day
// that loses nothing is not touched at all, so its mtime still says when it was last written to.
package splice.core.storage

import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.io.IOException
import java.io.Writer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// why: the longest one wait for a rewrite of every day on disk. The same order as the delete's own
// wait: a lane busy with a turn's appends is normal, a lane that never answers is not.
private const val KEEP_WAIT_MS = 120_000L

/** Whether one stored line survives a cut. Asked once per line, in the order the file holds them. */
public fun interface DayLineKeep {
    public fun keeps(line: String): Boolean
}

/** What a cut removed from a day store: [lines] rows gone, and [files] days that went entirely. */
public data class DayLinesGone(val lines: Long, val files: Int)

/**
 * The row-exact cut over one day store's files, beside [DayFiles]'s whole-store purge.
 *
 * [dir] and [prefix] name the same store [ActivityDays] writes and [DayFiles] reads. The cut takes
 * that store's day lock, so it is ordered against the store's own appends and its midnight sweep.
 */
public class DayLinesKept(
    private val dir: Path,
    private val prefix: String,
) {
    private val files = DayFiles(dir, prefix)

    /**
     * Drop every line [keep] refuses, from every day on disk, and say what went.
     *
     * Throws when the lane refuses the work, when it does not answer inside [KEEP_WAIT_MS], or when
     * a day could not be read or replaced: a caller that reports a deletion must not report one
     * that did not happen. Call it from an I/O dispatcher.
     */
    @Throws(IOException::class, InterruptedException::class)
    public fun keepOnly(keep: DayLineKeep): DayLinesGone {
        val ready = CountDownLatch(1)
        val result = AtomicReference<Result<DayLinesGone>?>(null)
        val queued = AsyncFileIo.submit {
            try {
                result.set(Cancellables.runCatchingCancellable { files.mutation.withLock { cut(keep) } })
            } finally {
                ready.countDown()
            }
        }
        if (!queued) throw IOException("the file lane refused the cut; nothing was removed")
        if (!ready.await(KEEP_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("the cut is still running; read the store again before retrying")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun cut(keep: DayLineKeep): DayLinesGone {
        var lines = 0L
        var gone = 0
        for (file in files.files().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
            val tally = weigh(file, keep)
            if (tally.dropped == 0L) continue
            lines += tally.dropped
            if (tally.kept == 0L) {
                if (!Files.deleteIfExists(file)) throw IOException("could not delete $file")
                gone += 1
            } else {
                rewrite(file, keep)
            }
        }
        return DayLinesGone(lines, gone)
    }

    /** How many of [file]'s lines [keep] refuses, and how many it keeps, read without rewriting. */
    private fun weigh(file: Path, keep: DayLineKeep): Tally {
        var dropped = 0L
        var kept = 0L
        DayLines(file).use { read ->
            while (true) {
                val line = read.next() ?: break
                if (keep.keeps(line)) kept += 1 else dropped += 1
            }
        }
        return Tally(dropped, kept)
    }

    /** [file] with only the lines [keep] accepts, through a 0600 sibling and one atomic move. */
    private fun rewrite(file: Path, keep: DayLineKeep) {
        val temp = file.resolveSibling("${file.fileName}.cut")
        try {
            SecureFile.createNew0600(temp, ByteArray(0))
            copyKept(file, temp, keep)
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun copyKept(from: Path, to: Path, keep: DayLineKeep) {
        Files.newBufferedWriter(to, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { out ->
            DayLines(from).use { read -> copyInto(read, out, keep) }
        }
    }

    private fun copyInto(read: DayLines, out: Writer, keep: DayLineKeep) {
        while (true) {
            val line = read.next() ?: break
            if (keep.keeps(line)) out.write(line + "\n")
        }
    }

    private data class Tally(val dropped: Long, val kept: Long)
}

/** One day file's lines, pulled one at a time, replacing bytes that are not text rather than
 *  throwing: a line splice cannot decode is still a line, and a cut must not stop on it. */
private class DayLines(file: Path) : AutoCloseable {
    private val reader = java.io.InputStreamReader(
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE),
    ).buffered()

    fun next(): String? = reader.readLine()

    override fun close() = reader.close()
}
