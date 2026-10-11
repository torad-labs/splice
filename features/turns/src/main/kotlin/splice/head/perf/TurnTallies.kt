// NEW: the perf rows each turn file held when last counted, read only from where the last count ended.
package splice.head.perf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

// why: a perf file is read in megabyte chunks, so one read never holds more of a multi-hundred-megabyte file than that.
private const val CHUNK_BYTES = 1 shl 20

// why: the first and last 4 KiB of what was counted tell an appended file from one rewritten in place.
private const val CHECK_BYTES = 4096

// why: a perf row ends at the line feed byte (10); the byte is compared directly so no row is decoded to find its end.
private const val ROW_END: Byte = 10

internal class TurnTally {
    val days = HashSet<LocalDate>()
    var rows = 0L
    var unknown = 0L

    fun add(day: LocalDate?) {
        rows += 1
        if (day == null) unknown += 1 else days += day
    }

    fun copy(): TurnTally = TurnTally().also { it.merge(this) }

    fun merge(other: TurnTally) {
        rows += other.rows
        unknown += other.unknown
        days += other.days
    }
}

/** The day a perf row says it was written, or null when it names none. */
internal fun interface TurnRowDay {
    fun of(line: String): LocalDate?
}

/** What one perf file held when it was last read: where its last whole row ended and a checksum of the bytes around
 *  there, so a later read can tell an appended file from a replaced one. */
private data class SeenTurns(val fileKey: String?, val consumed: Long, val checksum: Long, val tally: TurnTally)

/**
 * The turn rows each perf file held when last counted. A count of years of turns re-parsed every row of every file on
 * each call (about 850 MB, 3 to 7 s on a live daemon, which kept Settings blank); a file already seen is read only
 * from where its last whole row ended. A file that was replaced, shrunk, or rewritten under that point (another file
 * key, a smaller size, a different checksum there) is read again from its start, so a rotation can never be missed.
 */
public class TurnTallies {
    private val seen = ConcurrentHashMap<Path, SeenTurns>()

    /** Forget everything: after a deletion no file is the one that was counted. */
    internal fun clear() = seen.clear()

    internal fun of(file: Path, rowDay: TurnRowDay): TurnTally {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw InvalidTurnFile()
        val fileKey = Files.getAttribute(file, "fileKey", LinkOption.NOFOLLOW_LINKS)?.toString()
        return FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val earlier = seen[file]?.takeIf { continues(it, channel, fileKey) }
            val tally = earlier?.tally?.copy() ?: TurnTally()
            val read = read(channel, earlier?.consumed ?: 0L, tally, rowDay)
            seen[file] = SeenTurns(fileKey, read.end, checksum(channel, read.end), tally.copy())
            // the partial row a writer has not finished counts now and is read whole next time
            if (read.partial.isNotEmpty()) tally.add(rowDay.of(String(read.partial, Charsets.UTF_8)))
            tally
        }
    }

    /** Whether the file is still the one [seen] counted, grown since: the same file, no shorter, its bytes there unchanged. */
    private fun continues(seen: SeenTurns, channel: FileChannel, fileKey: String?): Boolean =
        seen.fileKey == fileKey && channel.size() >= seen.consumed &&
            checksum(channel, seen.consumed) == seen.checksum

    private data class Read(val end: Long, val partial: ByteArray)

    /** Counts every newline-ended row from [start], returning where the last one ended and the unfinished tail. */
    private fun read(channel: FileChannel, start: Long, tally: TurnTally, rowDay: TurnRowDay): Read {
        val buffer = ByteBuffer.allocate(CHUNK_BYTES)
        val bytes = buffer.array()
        val row = ByteArrayOutputStream()
        var chunkStart = start
        var end = start
        channel.position(start)
        while (true) {
            val count = channel.read(buffer)
            if (count <= 0) break
            var from = 0
            for (at in 0 until count) {
                if (bytes[at] == ROW_END) {
                    row.write(bytes, from, at - from)
                    tally.add(rowDay.of(String(row.toByteArray(), Charsets.UTF_8)))
                    row.reset()
                    from = at + 1
                    end = chunkStart + from
                }
            }
            row.write(bytes, from, count - from)
            chunkStart += count
            buffer.clear()
        }
        return Read(end, row.toByteArray())
    }

    /** A checksum of the first and the last bytes before [end]: an append leaves both as they were. */
    private fun checksum(channel: FileChannel, end: Long): Long {
        val crc = CRC32()
        for (from in longArrayOf(0L, (end - CHECK_BYTES).coerceAtLeast(0L))) {
            val buffer = ByteBuffer.allocate(CHECK_BYTES)
            channel.read(buffer, from)
            buffer.flip()
            val usable = minOf(buffer.remaining().toLong(), (end - from).coerceAtLeast(0L)).toInt()
            crc.update(buffer.array(), 0, usable)
        }
        return crc.value
    }
}
