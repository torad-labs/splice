// Split out of ActivityDays.kt (2026-10-13, the concentration ratchet): one store's day files with
// no window, as ActivityDays.kt's header draws the line — the store that writes owns the window and
// every sweep, and a reader of every day on disk or a purge of all of them comes here. The files'
// layout, retention and failure contract are stated in that header; the declarations are unchanged,
// save that DayDeleteMarker is internal, because ActivityDays clears it on each append.
package splice.core.storage

import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A day file's own name, then JsonlSink's lock, its newest rolled generation and the trace body files beside it.
 *  The older generations are [DayGenerations]'. */
internal val daySiblings = listOf(
    "",
    ".lock",
    NEWEST_ROLLED,
    DAY_BODY_SUFFIX,
    DAY_BODY_V2_SUFFIX,
    DAY_BODY_EVICTED_SUFFIX,
    "$DAY_BODY_EVICTED_SUFFIX.tmp",
)

// A head's trace can span gigabytes; counting its rows before deletion must not time out at one screen's budget.
private const val DELETE_WAIT_MS = 120_000L

/** What one store physically holds, including rolled-only days: its rows, its bytes, and its oldest day holding data
 *  (null when none does). */
public data class DayInventory(
    val rows: Long,
    val bytes: Long,
    val oldest: LocalDate?,
)

/** A store's delete state is a sibling of its day files, not itself a day file. */
internal class DayDeleteMarker(private val dir: Path, prefix: String, private val ownerOnly: Boolean) {
    private val path = dir.resolve("$prefix.deleted")

    fun exists(): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    fun mark() {
        if (Files.isSymbolicLink(dir)) throw IOException("day directory is a symlink: $dir")
        if (ownerOnly) {
            val _ = SecureFile.ownerOnlyDirectory(dir)
        } else {
            Files.createDirectories(dir)
        }
        SecureFile.writeAtomic0600(path, "deleted\n")
    }

    fun clear() {
        val _ = Files.deleteIfExists(path)
    }
}

/** Bounded wait for the file lane, keeping refusal and incomplete work distinct from a completed delete. */
private class DayDeleteWait {
    val ready = CountDownLatch(1)
    val result = AtomicReference<Result<DayInventory>?>(null)

    @Throws(IOException::class, InterruptedException::class)
    fun await(queued: Boolean): DayInventory {
        if (!queued) throw IOException("the file lane refused the delete; nothing was removed")
        if (!ready.await(DELETE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("the delete is still running; read inventory before retrying")
        }
        return checkNotNull(result.get()).getOrThrow()
    }
}

/** The filesystem deletion boundary; failure injection still runs inventory, locks and the real purge. */
public fun interface DayFileRemoval {
    public fun remove(file: Path): Boolean
}

/** V4-273: one store's day files whatever their age, with no window: every line on disk (the
 *  `splice trace` reader) and the purge of all of them. The window, and every sweep, belong to the
 *  store that writes the files ([ActivityDays]). */
public class DayFiles @JvmOverloads constructor(
    private val dir: Path,
    private val prefix: String,
    private val ownerOnly: Boolean = false,
    private val removal: DayFileRemoval = DayFileRemoval(Files::deleteIfExists),
) {
    private val marker = DayDeleteMarker(dir, prefix, ownerOnly)
    private val metadata = DayFileInventory(prefix)
    private val backward = BackwardLines()
    internal val mutation = DayMutationLock(dir, prefix)

    /** Every line of every day on disk, oldest day first. */
    public fun lines(): Sequence<String> = linesFrom(LocalDate.MIN)

    /** V4-338: every line of every day on disk, NEWEST first (the newest day's file from its end, then that
     *  day's rolled half, then the day before), to [visit] one at a time until it answers false. Holds no
     *  line whole unless [visit] asks for its text (V4-343: each is a DayLine, read as far as it is asked),
     *  where [lines] reads each day whole: the `splice trace` reader stops once it holds its turns.
     *  A directory or a day that cannot be read throws why, as [lines] does. */
    @Throws(IOException::class)
    public fun newestFirst(visit: LineVisit) {
        for (file in files()) {
            if (!backward.read(file, visit)) return
        }
    }

    /** V4-343: every file of every day on disk, the newest day first, each day's live file before its rolled
     *  generations, newest first: the files [newestFirst] reads, for a reader that reads each one its own way and
     *  when it likes (the trace count reads several at once, and only what each gained since it last read it).
     *  The `.1` generation is named whether or not it is there. A directory that cannot be listed throws why. */
    @Throws(IOException::class)
    public fun files(oldest: LocalDate = LocalDate.MIN): List<Path> =
        days().filter { (date, _) -> !date.isBefore(oldest) }.asReversed()
            .flatMap { (_, file) -> listOf(file) + DayGenerations.oldestFirst(file).asReversed() }
            .filter { metadata.regularSize(it) != null || !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /** Count actual day files, including an empty base or a rolled half without its base. Symlink targets
     *  are not part of this store, so neither their bytes nor their lines enter the inventory. */
    @Throws(IOException::class)
    public fun inventory(retentionDays: Int): DayInventory = metadata.inventory(days(), retentionDays)

    /** A delete marker is a non-day file. It survives a restart, but a successfully appended row clears it. */
    public fun deleted(): Boolean = marker.exists()

    /** Delete after earlier appends on the same file lane, then mark the empty store before later writes.
     *  A rejected or timed-out lane never claims the deletion finished. Call from an I/O dispatcher. */
    @Throws(IOException::class, InterruptedException::class)
    public fun deleteKept(retentionDays: Int): DayInventory {
        val wait = DayDeleteWait()
        val queued = AsyncFileIo.submit {
            try {
                val attempt = Cancellables.runCatchingCancellable {
                    if (ownerOnly) {
                        val _ = SecureFile.ownerOnlyDirectory(dir)
                    } else {
                        Files.createDirectories(dir)
                    }
                    mutation.withLock {
                        val before = inventory(retentionDays)
                        when (val purged = purge()) {
                            is DayPurge.Unlisted -> throw IOException("cannot list ${purged.dir}", purged.failure)
                            is DayPurge.Listed -> purged.failed.entries.firstOrNull()?.let { (file, failure) ->
                                throw IOException("could not delete $file", failure)
                            }
                        }
                        marker.mark()
                        before
                    }
                }
                wait.result.set(attempt)
            } finally {
                wait.ready.countDown()
            }
        }
        return wait.await(queued)
    }

    /** V4-343: what [visit] answers of [file] open as a [LineFile]; null when the file is not there. Any other
     *  failure throws. */
    @Throws(IOException::class)
    public fun <T : Any> open(file: Path, visit: FileVisit<T>): T? = backward.open(file, visit)

    /** V4-174: deletes EVERY day file of this store, whatever its age, with JsonlSink's siblings —
     *  the `splice trace --purge` verb. Answers what went and what did not (V4-286: it answered the
     *  files it listed, deleted or not, and a directory it could not list as one with no files). */
    public fun purge(): DayPurge = Cancellables.runCatchingCancellable {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            DayPurge.Listed(emptyList(), emptyMap())
        } else {
            mutation.withLock {
                val failed = LinkedHashMap<Path, Throwable>()
                val deleted = days().map { (_, file) -> file }.filter { file ->
                    val undeleted = deleteDay(file)
                    failed.putAll(undeleted)
                    file !in undeleted
                }
                DayPurge.Listed(deleted, failed)
            }
        }
    }.getOrElse { failure -> DayPurge.Unlisted(dir, failure) }

    /** Every line of the days from [oldest] on, oldest day first, each day's rolled half first. */
    @Throws(IOException::class)
    internal fun linesFrom(oldest: LocalDate): Sequence<String> =
        days().filter { (date, _) -> !date.isBefore(oldest) }
            .asSequence()
            .flatMap { (_, file) -> (readRolled(file) + readLines(file)).asSequence() }

    /** Deletes every day file dated before [oldest], with its siblings. */
    @Throws(IOException::class)
    internal fun deleteBefore(oldest: LocalDate) {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) return
        mutation.withLock {
            for ((date, file) in days()) {
                // A day that cannot be deleted now is swept again next time, and reads already leave it out.
                if (date.isBefore(oldest)) {
                    val _ = deleteDay(file)
                }
            }
        }
    }

    /** Deletes the day file and the siblings JsonlSink writes beside it; answers each one still on
     *  disk, with why its delete failed. */
    private fun deleteDay(file: Path): Map<Path, Throwable> =
        daySiblings.map { suffix -> file.resolveSibling("${file.fileName}$suffix") }
            .plus(DayGenerations.oldestFirst(file))
            .distinct()
            .mapNotNull { sibling ->
                Cancellables.runCatchingCancellable { removal.remove(sibling) }
                    .exceptionOrNull()?.let { sibling to it }
            }
            .toMap()

    private fun days(): List<Pair<LocalDate, Path>> {
        if (Files.isSymbolicLink(dir)) throw IOException("day directory is a symlink: $dir")
        return DirectoryEntries.of(dir)
            .mapNotNull { entry -> metadata.dateOf(entry)?.let { it to dir.resolve("$prefix-$it.jsonl") } }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    /** The day's rolled generations, oldest first, read BEFORE the live file so the day comes
     *  back whole and in order (v0.4.0 release: the read skipped them, so a day that rotated started halfway). */
    private fun readRolled(file: Path): List<String> = DayGenerations.oldestFirst(file).flatMap { readLines(it) }

    /** [file]'s lines, decoded leniently: a malformed byte reads as U+FFFD and costs only its own line,
     *  as JsonlSink.readTailAt reads. None when the file is not there; any other failure throws. */
    private fun readLines(file: Path): List<String> {
        if (metadata.regularSize(file) == null) return emptyList()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return try {
            InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder).useLines { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }
}

/** What [DayFiles.purge] did. */
public sealed class DayPurge {
    /** [dir] exists and could not be listed, so nothing was deleted. */
    public data class Unlisted(val dir: Path, val failure: Throwable) : DayPurge()

    /** The day files that were deleted, and each file still on disk with why its delete failed. */
    public data class Listed(val deleted: List<Path>, val failed: Map<Path, Throwable>) : DayPurge()
}
