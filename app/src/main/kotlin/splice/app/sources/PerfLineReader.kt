// NEW: byte-positioned, replacing UTF-8 perf lines, including torn tails and every line ending.
package splice.app.sources

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// why: 64 KiB frames about sixty synthetic 1 KiB rows per read while keeping one fixed scratch buffer.
private const val PERF_LINE_BUFFER_BYTES = 65_536

/** A byte-positioned UTF-8 line reader, including CR, LF, CRLF and the unterminated last line. */
internal class PerfLineReader(private val channel: FileChannel, start: Long, private val limit: Long) {
    private val buffer = ByteBuffer.allocate(PERF_LINE_BUFFER_BYTES)
    private val input = buffer.array()
    private val bytes = ByteArrayOutputStream()
    private var offset = 0
    private var available = 0
    private var ending = PerfLineEnding.NONE

    var position: Long = start
        private set
    var terminated: Boolean = false
        private set
    var trailingCr: Boolean = false
        private set

    fun next(): String? {
        if (position >= limit) return null
        bytes.reset()
        terminated = false
        trailingCr = false
        ending = PerfLineEnding.NONE
        while (position < limit && !terminated) {
            if (!fill()) return decoded()
            val start = offset
            while (offset < available && !lineBreak(input[offset])) {
                offset++
            }
            bytes.write(input, start, offset - start)
            position += offset - start
            if (offset < available) endLine(input[offset++])
        }
        return decoded()
    }

    private fun lineBreak(value: Byte): Boolean = value == '\n'.code.toByte() || value == '\r'.code.toByte()

    private fun fill(): Boolean {
        if (offset < available) return true
        buffer.clear()
        buffer.limit(minOf(buffer.capacity().toLong(), limit - position).toInt())
        available = channel.read(buffer, position)
        offset = 0
        return available > 0
    }

    private fun endLine(value: Byte) {
        position++
        terminated = true
        ending = if (value == '\n'.code.toByte()) PerfLineEnding.LF else PerfLineEnding.CR
        if (ending == PerfLineEnding.CR) carriageReturn()
    }

    private fun carriageReturn() {
        if (position >= limit) {
            trailingCr = true
        } else if (fill() && input[offset] == '\n'.code.toByte()) {
            offset++
            position++
            ending = PerfLineEnding.CRLF
        }
    }

    /** Hash the raw bytes actually decoded, never a later reread that could have been rewritten. */
    fun hashTo(output: OutputStream) {
        bytes.writeTo(output)
        when (ending) {
            PerfLineEnding.NONE -> Unit
            PerfLineEnding.LF -> output.write('\n'.code)
            PerfLineEnding.CR -> output.write('\r'.code)
            PerfLineEnding.CRLF -> {
                output.write('\r'.code)
                output.write('\n'.code)
            }
        }
    }

    private fun decoded(): String? {
        if (!terminated && bytes.size() == 0) return null
        // Charset decoding replaces torn UTF-8, just as the previous InputStreamReader did.
        return bytes.toString(Charsets.UTF_8)
    }
}

private enum class PerfLineEnding { NONE, LF, CR, CRLF }
