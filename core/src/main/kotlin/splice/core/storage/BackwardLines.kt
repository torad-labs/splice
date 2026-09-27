// NEW: V4-338 — one file's lines from its END, one at a time, for a reader that wants the newest lines of a
// store it must not hold: `splice trace <head> --last N` stops as soon as it holds its N turns, and a
// count over a 4 GB trace store keeps one line. Before this, a day file was read whole into a list
// (DayFiles.readLines), and claudex's 3.7 GB of 2-3 MB lines ended the CLI in an OutOfMemoryError.
//
// Lines are the ones BufferedReader.readLine gives, last first: split at \n, \r and \r\n, a final
// terminator ending the last line rather than starting an empty one. Each is decoded leniently, as
// DayFiles.readLines decodes, so a malformed byte reads as U+FFFD and costs only its own line; no line
// terminator is ever part of a UTF-8 sequence, so a line decoded alone reads as it does in the stream.
//
// V4-343: a line is handed over as a [DayLine], read no further than its reader asks. Every line was
// decoded to a String before its reader saw it, and on claudex's 3.6 GB store that decode was 21% of the
// console trace page's 21.4 s, most of the rest being kotlinx skipping the bodies of those Strings; a count
// that needs a few fields of each record now streams its bytes and decodes none of the body. The search for
// the line before steps eight bytes at a time (ByteWords) while none of them ends a line. A [LineFile] reads
// the lines of any stretch between two terminators, which are the ones the whole file splits into there, and
// says where its settled lines end: in a file that only grows, a reader that keeps what it counted before that
// end reads only what was appended after it.
package splice.core.storage

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Hears lines one at a time and answers whether to go on. */
public fun interface LineVisit {
    public fun line(line: DayLine): Boolean
}

/** Hears one open file and answers what it read of it: a reader that reads each file its own way. */
public fun interface FileVisit<T : Any> {
    public fun file(file: LineFile): T
}

/** One line of a file, read no further than its reader asks: [bytes] streams it a read at a time, [text]
 *  decodes it whole. It reads the file it came from, so it is good only inside the visit it was handed to. */
public class DayLine internal constructor(
    private val reads: ChannelReads,
    private val start: Long,
    private val end: Long,
) {
    /** Its bytes in order, its terminator not among them, read from the file as they are asked for. */
    public fun bytes(): InputStream = SpanStream(reads, start, end)

    /** The whole line, decoded leniently as DayFiles.readLines decodes it: a malformed byte reads as U+FFFD. */
    @Throws(IOException::class)
    public fun text(): String {
        val bytes = ByteArray((end - start).toInt())
        reads.fill(ByteBuffer.wrap(bytes), start)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}

/** Positional reads of one open file, shared by the cursor that finds its lines and the lines it hands out. */
internal class ChannelReads(private val channel: FileChannel) {
    fun size(): Long = channel.size()

    /** Reads [into] full from [position]. The size was read at the start and a day file only grows, so a
     *  short file here is one that was cut under the read, and that is said. */
    fun fill(into: ByteBuffer, position: Long) {
        var at = position
        while (into.hasRemaining()) {
            val read = channel.read(into, at)
            if (read < 0) throw IOException("the file shrank while it was read backward, at byte $at")
            at += read
        }
    }
}

/** The bytes from [at] up to [end] of a file, as a stream that reads them when asked. */
private class SpanStream(private val reads: ChannelReads, private var at: Long, private val end: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toUByte().toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (at >= end) return -1
        val n = minOf(len.toLong(), end - at).toInt()
        reads.fill(ByteBuffer.wrap(b, off, n), at)
        at += n
        return n
    }
}

// why: the bytes scanned per read while looking for the line before; a few pages, so a 2-3 MB trace
// line costs a few dozen reads rather than a buffer the size of the file
private const val WINDOW_BYTES = 64 * 1024

private val LF: Byte = '\n'.code.toByte()
private val CR: Byte = '\r'.code.toByte()
private val LF_WORD = ByteWords.repeated(LF)
private val CR_WORD = ByteWords.repeated(CR)

/** One open file of lines, its size read once, at the open: a line appended after it is not read, and the torn
 *  half of an append in flight reads as the line it is. It reads the file it came from, so it is good only
 *  inside the visit it was handed to. */
public class LineFile internal constructor(private val reads: ChannelReads) {
    public val size: Long = reads.size()

    /** Just past the last terminator no byte appended later can change, or 0: in a file that only grows, every
     *  line before it is whole and final. A \r ending the file is not one, since a \n appended next would join
     *  it into one \r\n. */
    public fun settledEnd(): Long = BackwardCursor(reads, 0L).settledBefore(size)

    /** The [count] bytes from [from], fewer when the file ends first; [from] is at most [size]. */
    public fun bytes(from: Long, count: Int): ByteArray =
        ByteArray(minOf(count.toLong(), size - from).toInt()).also { reads.fill(ByteBuffer.wrap(it), from) }

    /** Visits the lines between [from] and [until], the last first, until [visit] answers false; false when it
     *  did. [from] starts a line (0, or just past a terminator) and [until] ends one or is [size], so these are
     *  the lines the whole file splits into there. */
    public fun lines(from: Long, until: Long, visit: LineVisit): Boolean =
        BackwardCursor(reads, from).each(until, visit)
}

/** Reads files' lines from their end. */
internal class BackwardLines {
    /** Visits [file]'s lines, the last first, until [visit] answers false; false when it did. A file that is
     *  not there has no lines; any other failure throws. */
    @Throws(IOException::class)
    fun read(file: Path, visit: LineVisit): Boolean = open(file) { it.lines(0L, it.size, visit) } ?: true

    /** What [visit] answers of [file] open as a [LineFile]; null when the file is not there. Any other failure
     *  throws. */
    @Throws(IOException::class)
    fun <T : Any> open(file: Path, visit: FileVisit<T>): T? {
        val channel = try {
            FileChannel.open(file, StandardOpenOption.READ)
        } catch (_: NoSuchFileException) {
            return null
        }
        return channel.use { visit.file(LineFile(ChannelReads(it))) }
    }
}

/** One open file read from its end, no further back than [floor], where the lines it reads start. */
private class BackwardCursor(private val reads: ChannelReads, private val floor: Long) {
    private val window = ByteArray(WINDOW_BYTES)
    private val words = ByteWords.view(window)
    private var windowStart = 0L
    private var windowEnd = 0L

    /** Visits each line before [until], the last first; false when [visit] stopped the read. */
    fun each(until: Long, visit: LineVisit): Boolean {
        var end = if (until <= floor) -1L else terminatorStart(until)
        var going = true
        while (going && end >= floor) {
            val cut = lastTerminatorBefore(end)
            going = visit.line(DayLine(reads, cut + 1, end))
            end = if (cut < floor) -1L else terminatorStart(cut + 1)
        }
        return going
    }

    /** Just past the last terminator before [end] that a byte appended at [end] cannot join, or [floor]. */
    fun settledBefore(end: Long): Long {
        if (end <= floor) return floor
        val last = lastTerminatorBefore(end)
        val open = last == end - 1 && byteAt(last) == CR
        return (if (open) lastTerminatorBefore(last) else last) + 1
    }

    /** Where the terminator ending just before [after] starts (\r\n is one terminator), or [after] when the
     *  byte before it ends no line. */
    private fun terminatorStart(after: Long): Long {
        val last = byteAt(after - 1)
        return when {
            last == LF && after - 2 >= floor && byteAt(after - 2) == CR -> after - 2
            last == LF || last == CR -> after - 1
            else -> after
        }
    }

    /** The offset of the last terminator byte before [end], or [floor] - 1 when the line runs from [floor]. */
    private fun lastTerminatorBefore(end: Long): Long {
        var at = end - 1
        while (at >= floor) {
            hold(at) // the scan below runs over the window's array
            var i = (at - windowStart).toInt()
            // V4-343: eight bytes a step while none of them ends a line, then the last few a byte at a time
            while (i >= Long.SIZE_BYTES - 1 && !endsAny(words.getLong(i - (Long.SIZE_BYTES - 1)))) i -= Long.SIZE_BYTES
            while (i >= 0 && !ends(window[i])) i -= 1
            if (i >= 0) return windowStart + i
            at = windowStart - 1
        }
        return floor - 1
    }

    private fun ends(byte: Byte): Boolean = byte == LF || byte == CR

    private fun endsAny(word: Long): Boolean = (ByteWords.equal(word, LF_WORD) or ByteWords.equal(word, CR_WORD)) != 0L

    private fun byteAt(offset: Long): Byte {
        hold(offset)
        return window[(offset - windowStart).toInt()]
    }

    /** Reads the window that ends at [offset] into [window], unless it already holds [offset]; it reaches no
     *  further back than [floor]. */
    private fun hold(offset: Long) {
        if (offset in windowStart until windowEnd) return
        windowStart = maxOf(floor, offset + 1 - WINDOW_BYTES)
        windowEnd = offset + 1
        reads.fill(ByteBuffer.wrap(window, 0, (windowEnd - windowStart).toInt()), windowStart)
    }
}
