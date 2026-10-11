// NEW: what a perf source's files held at one moment, so a later look can tell appended files from replaced or rewritten ones.
package splice.app.sources

import splice.core.perf.PerfArchiveName
import splice.usage.perf.PerfRowsWindow
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

/** The files of one perf source (both live generations and every archive) at one moment, and which of them may grow. */
internal class PerfSourceStamp(
    private val marks: List<PerfFileMark>,
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

    /** The files as they are now; null when one cannot be marked, so nothing may be reused. */
    fun stamp(): PerfSourceStamp? {
        val marks = (list() ?: return null).map { reader.of(it) ?: return null }
        return PerfSourceStamp(marks, live, reader)
    }

    /** Whether the files are the ones [stamp] was taken of, none added or gone, only the live one grown by appends. */
    fun unchanged(stamp: PerfSourceStamp): Boolean = stamp.covers(list().orEmpty()) && stamp.holds()
}

/** A read the request log refused for a row it could not parse, and the earliest hour that read covered. A torn row is
 *  not mended by what is appended after it, so while the files are only appended to the refusal stands. */
internal class PerfRefusal(private val files: PerfReadFiles) {
    private var stamp: PerfSourceStamp? = null
    private var since = 0L

    /** Whether the refusal noted earlier still holds for a read from [from]: that read covers no less, and no file changed. */
    fun stands(from: Long): Boolean = stamp?.let { from <= since && files.unchanged(it) } == true

    /** Notes a read of [work] begun when the files were as [before]: a refusal for a torn row stands, any other answer clears it. */
    fun note(before: PerfSourceStamp?, from: Long, work: PerfRowsWindow) {
        stamp = before.takeIf { work.readError == null && work.skipped != 0 }
        since = from
    }
}
