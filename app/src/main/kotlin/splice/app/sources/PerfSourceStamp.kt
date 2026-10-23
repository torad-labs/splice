// NEW: what a perf source's files held at one moment, so a later look can tell appended files from replaced or rewritten ones.
// Oct 23, 2026: [PerfReadFiles.opens] tells the backfill from which hours a read opens the archive a torn line lies in,
// and [PerfReadFiles.since] tells a rotation from a rewrite or an added file, so the probe check keeps its torn hours across one.
package splice.app.sources

import kotlinx.serialization.Serializable
import splice.core.perf.PerfArchiveName
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

// why: the first and last 4 KiB of a file tell an appended file from one rewritten in place, as the turn counter does.
private const val MARK_BYTES = 4096

/** One file as it was: its identity, its size and a checksum of its first bytes and of the bytes ending at that size. */
internal data class PerfFileMark(val path: Path, val fileKey: String?, val size: Long, val checksum: Long)

/** Reads the marks a stamp is made of. */
internal class PerfFileMarks {
    /** The file's mark now, or null when it is missing, not a regular file or unreadable. */
    fun of(path: Path): PerfFileMark? = try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            null
        } else {
            val key = Files.getAttribute(path, "fileKey", LinkOption.NOFOLLOW_LINKS)?.toString()
            val size = Files.size(path)
            PerfFileMark(path, key, size, checksum(path, size))
        }
    } catch (_: IOException) {
        null
    }

    /** A checksum of the first bytes and of the last bytes before [end]. */
    fun checksum(path: Path, end: Long): Long {
        val crc = CRC32()
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            for (from in longArrayOf(0L, (end - MARK_BYTES).coerceAtLeast(0L))) {
                val buffer = ByteBuffer.allocate(MARK_BYTES)
                channel.read(buffer, from)
                val usable = minOf(buffer.position().toLong(), (end - from).coerceAtLeast(0L)).toInt()
                crc.update(buffer.array(), 0, usable)
            }
        }
        return crc.value
    }
}

/** What became of a stamp's files: nothing that can mend an hour, a file came with rows the stamp never saw, or one
 *  of them was rewritten. */
internal enum class PerfFilesSince { KEPT, ADDED, REWRITTEN }

/** The files of one perf source (both live generations and every archive) at one moment, and which of them may grow. */
internal class PerfSourceStamp(
    internal val marks: List<PerfFileMark>,
    private val growing: Path,
    private val reader: PerfFileMarks = PerfFileMarks(),
) {
    /** Whether the same files are there now, the live one grown by appends only and every other one untouched. */
    fun holds(): Boolean = try {
        marks.all { mark ->
            val now = reader.of(mark.path)
            when {
                now == null || now.fileKey != mark.fileKey -> false
                mark.path == growing -> now.size >= mark.size && reader.checksum(mark.path, mark.size) == mark.checksum
                else -> now.size == mark.size && now.checksum == mark.checksum
            }
        }
    } catch (_: IOException) {
        false
    }

    /** Whether [files] are the files this stamp was taken of, none added and none gone. */
    fun covers(files: List<Path>): Boolean = files.toSet() == marks.map { it.path }.toSet()

    /** What became of this stamp's files among [files], or null when one went while they were looked at. A marked file
     *  whose name is still there and whose bytes begin no file was REWRITTEN. A file other than the live one that
     *  [brings] rows no marked file held, such as a restored archive, was ADDED. A rotation does neither: it renames
     *  the live file to `.1` (grown by appends), copies `.1` whole into the archive and deletes archives past the
     *  window. */
    fun since(files: List<Path>): PerfFilesSince? {
        val now = files.map { reader.of(it) ?: return null }
        return try {
            // Each file is tried against the one of its own name first, which holds it unless a rotation moved it.
            when {
                marks.any { mark ->
                    now.any { it.path == mark.path } && now.sortedBy { it.path != mark.path }.none { carries(it, mark) }
                } -> PerfFilesSince.REWRITTEN
                now.any { file -> file.path != growing && brings(file, now) } -> PerfFilesSince.ADDED
                else -> PerfFilesSince.KEPT
            }
        } catch (_: NoSuchFileException) {
            null
        } catch (_: IOException) {
            PerfFilesSince.REWRITTEN
        }
    }

    /** Whether [file] holds rows no marked file held: it begins with no mark's bytes, and no marked file still among
     *  [now] begins with its bytes, as `.1` does while a rotation is still copying it into the archive. */
    private fun brings(file: PerfFileMark, now: List<PerfFileMark>): Boolean =
        marks.sortedBy { it.path != file.path }.none { mark ->
            carries(file, mark) ||
                now.firstOrNull { it.path == mark.path && it.path != file.path }?.let { carries(it, file) } == true
        }

    /** Whether [file] begins with the bytes [mark] was taken of. */
    private fun carries(file: PerfFileMark, mark: PerfFileMark): Boolean = when {
        file.size == mark.size -> file.checksum == mark.checksum
        file.size > mark.size -> reader.checksum(file.path, mark.size) == mark.checksum
        else -> false
    }
}

/** The files one perf source reads: every archive, then the live generations that exist. */
internal class PerfReadFiles(
    private val live: Path,
    private val archiveDir: Path?,
    private val archiveName: PerfArchiveName,
    private val generations: List<Path>,
) {
    private val reader = PerfFileMarks()

    /** Every file the source reads right now, or null when the archive directory cannot be listed. */
    fun list(): List<Path>? {
        val archives = archiveDir?.let { dir ->
            try {
                Files.newDirectoryStream(dir).use { stream -> stream.toList() }
                    .filter { archiveName.rotatedAt(it.fileName.toString()) != null }
            } catch (_: NoSuchFileException) {
                emptyList()
            } catch (_: IOException) {
                return null
            }
        }.orEmpty()
        return archives + generations.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    /** A stamp taken earlier, from its [marks], judged against this source's live file. */
    fun stampOf(marks: List<PerfFileMark>): PerfSourceStamp = PerfSourceStamp(marks, live, reader)

    /** Where state named [name] is kept across restarts: beside the live file, under a name no archive matches. */
    fun stateFile(name: String): Path =
        live.resolveSibling("${live.fileName.toString().removeSuffix(".jsonl")}-$name.json")

    /** The files as they are now; null when one cannot be marked, so nothing may be reused. */
    fun stamp(): PerfSourceStamp? {
        val marks = (list() ?: return null).map { reader.of(it) ?: return null }
        return PerfSourceStamp(marks, live, reader)
    }

    /** Whether a read from [from] opens the generation archived at [rotatedAt], by the reader's own test for skipping
     *  an archive unopened. A live generation (null) is opened by every read. */
    fun opens(rotatedAt: Long?, from: Long): Boolean = rotatedAt == null || !archiveName.endsBefore(rotatedAt, from)

    /** Whether the files are the ones [stamp] was taken of, none added or gone, only the live one grown by appends. */
    fun unchanged(stamp: PerfSourceStamp): Boolean = stamp.covers(list().orEmpty()) && stamp.holds()

    /** What became of [stamp]'s files. A file that went while they were looked at was moved by a rotation in flight, so
     *  they are looked at once more; files that cannot be listed count as rewritten, so nothing judged from them stays. */
    fun since(stamp: PerfSourceStamp): PerfFilesSince = look(stamp) ?: look(stamp) ?: PerfFilesSince.REWRITTEN

    private fun look(stamp: PerfSourceStamp): PerfFilesSince? {
        val files = list() ?: return PerfFilesSince.REWRITTEN
        return stamp.since(files)
    }

    /** The archived generations with their rotation seconds, oldest first. No archive directory is quiet (nothing has
     *  rotated out yet, or the archive is off); any other failure to list it goes to [errors] as a read error, because
     *  the history it holds may be the window's. */
    fun archived(errors: MutableList<String>): List<Pair<Path, Long>> {
        val dir = archiveDir ?: return emptyList()
        val listed = Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
        listed.exceptionOrNull()?.takeUnless { it is NoSuchFileException }
            ?.let { errors += "${dir.fileName}: ${SafeFailureText.render(it)}" }
        return listed.getOrDefault(emptyList())
            .mapNotNull { path -> archiveName.rotatedAt(path.fileName.toString())?.let { path to it } }
            .sortedBy { it.second }
    }

    /** Each live generation's file key, for telling a rotation during a read. An absent generation has no fileKey;
     *  null is the normal reading and rotation is judged by comparing the SAME two reads before and after, so a null
     *  on both passes correctly reports 'no rotation'. */
    fun keys(): List<Any?> = generations.map { generation ->
        try { Files.getAttribute(generation, "fileKey") } catch (_: IOException) { null }
    }
}

/** One file mark as a stamp is kept on disk (ProbeJudgments). */
@Serializable
internal data class SavedPerfMark(val path: String, val fileKey: String? = null, val size: Long, val checksum: Long)
