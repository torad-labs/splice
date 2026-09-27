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
// the line before steps eight bytes at a time (ByteWords) while none of them ends a line.
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

/** Reads files' lines from their end. */
internal class BackwardLines {
    /** Visits [file]'s lines, the last first, until [visit] answers false; false when it did. A file that is
     *  not there has no lines; any other failure throws. */
    @Throws(IOException::class)
    fun read(file: Path, visit: LineVisit): Boolean {
        val channel = try {
            FileChannel.open(file, StandardOpenOption.READ)
        } catch (_: NoSuchFileException) {
            return true
        }
        return channel.use { BackwardCursor(ChannelReads(it)).each(visit) }
    }
}

/** One open file read from its end. Its size is read once, at the start: a line appended after it is not
 *  read, and the torn half of an append in flight reads as the line it is. */
private class BackwardCursor(private val reads: ChannelReads) {
    private val window = ByteArray(WINDOW_BYTES)
    private val words = ByteWords.view(window)
    private var windowStart = 0L
    private var windowEnd = 0L

    /** Visits each line, the last first; false when [visit] stopped the read. */
    fun each(visit: LineVisit): Boolean {
        val size = reads.size()
        var end = if (size == 0L) -1L else terminatorStart(size)
        var going = true
        while (going && end >= 0) {
            val cut = lastTerminatorBefore(end)
            going = visit.line(DayLine(reads, cut + 1, end))
            end = if (cut < 0) -1L else terminatorStart(cut + 1)
        }
        return going
    }

    /** Where the terminator ending just before [after] starts (\r\n is one terminator), or [after] when the
     *  byte before it ends no line. */
    private fun terminatorStart(after: Long): Long {
        val last = byteAt(after - 1)
        return when {
            last == LF && after >= 2 && byteAt(after - 2) == CR -> after - 2
            last == LF || last == CR -> after - 1
            else -> after
        }
    }

    /** The offset of the last terminator byte before [end], or -1 when the line runs from the file's start. */
    private fun lastTerminatorBefore(end: Long): Long {
        var at = end - 1
        while (at >= 0) {
            hold(at) // the scan below runs over the window's array
            var i = (at - windowStart).toInt()
            // V4-343: eight bytes a step while none of them ends a line, then the last few a byte at a time
            while (i >= Long.SIZE_BYTES - 1 && !endsAny(words.getLong(i - (Long.SIZE_BYTES - 1)))) i -= Long.SIZE_BYTES
            while (i >= 0 && !ends(window[i])) i -= 1
            if (i >= 0) return windowStart + i
            at = windowStart - 1
        }
        return -1L
    }

    private fun ends(byte: Byte): Boolean = byte == LF || byte == CR

    private fun endsAny(word: Long): Boolean = (ByteWords.equal(word, LF_WORD) or ByteWords.equal(word, CR_WORD)) != 0L

    private fun byteAt(offset: Long): Byte {
        hold(offset)
        return window[(offset - windowStart).toInt()]
    }

    /** Reads the window that ends at [offset] into [window], unless it already holds [offset]. */
    private fun hold(offset: Long) {
        if (offset in windowStart until windowEnd) return
        windowStart = maxOf(0L, offset + 1 - WINDOW_BYTES)
        windowEnd = offset + 1
        reads.fill(ByteBuffer.wrap(window, 0, (windowEnd - windowStart).toInt()), windowStart)
    }
}
