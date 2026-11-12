// NEW: count each source line through one fixed buffer before constructing its charged text.
package splice.core.memory

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// why: one fixed 8 KiB scan buffer counts arbitrarily long entries before any text allocation.
private const val LINE_SCAN_BYTES = 8192

/** A bounded forward framer. Lookahead inspects bytes and never materializes another line. */
public class HeapLines(path: Path, private val heap: HeapReservations) : AutoCloseable {
    private val scanLease = heap.reserve(LINE_SCAN_BYTES.toLong()) ?: throw HeapCapacityException()
    private val buffer = ByteArray(LINE_SCAN_BYTES).also { HeapOwners.keep(it, scanLease) }
    private val bytes = ByteBuffer.wrap(buffer)
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val end = channel.size()
    private var at = 0L
    private var cursor = 0
    private var count = 0

    public fun hasNext(): Boolean = at < end

    public fun next(): HeapText {
        check(hasNext())
        val start = at
        while (peek() >= 0 && !separator(peek())) advance()
        val stop = at
        if (peek() >= 0) {
            val separator = peek()
            advance()
            if (separator == '\r'.code && peek() == '\n'.code) advance()
        }
        return materialize(start, stop)
    }

    private fun materialize(start: Long, stop: Long): HeapText {
        var returned = false
        try {
            if (stop - start >= Int.MAX_VALUE) throw HeapCapacityException()
            return HeapText.Reader.read(Span(channel, start, stop), stop - start, heap).also { returned = true }
        } finally {
            if (!returned) {
                at = start
                cursor = 0
                count = 0
            }
        }
    }

    private fun separator(value: Int): Boolean = value == '\n'.code || value == '\r'.code

    private fun advance() {
        cursor++
        at++
    }

    private fun peek(): Int {
        if (at >= end) return -1
        if (cursor == count) {
            bytes.clear()
            bytes.limit(minOf(buffer.size.toLong(), end - at).toInt())
            count = channel.read(bytes, at)
            cursor = 0
            if (count <= 0) throw IOException("heap-budgeted line source shrank while reading")
        }
        return buffer[cursor].toUByte().toInt()
    }

    override fun close() {
        channel.close()
    }

    private class Span(private val channel: FileChannel, private var at: Long, private val end: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toUByte().toInt()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= end) return -1
            val requested = minOf(length.toLong(), end - at).toInt()
            val count = channel.read(ByteBuffer.wrap(bytes, offset, requested), at)
            if (count <= 0) throw IOException("heap-budgeted line source shrank while decoding")
            at += count
            return count
        }
    }
}
