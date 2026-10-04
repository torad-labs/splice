// NEW: byte-positioned, replacing UTF-8 perf lines, including torn tails and every line ending.
package splice.app.sources

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel

/** A byte-positioned UTF-8 line reader, including CR, LF, CRLF and the unterminated last line. */
internal class PerfLineReader(channel: FileChannel, start: Long, private val limit: Long) {
    private val input = BufferedInputStream(Channels.newInputStream(channel.position(start)))
    private val bytes = ByteArrayOutputStream()
    private var pending = -1
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
            val value = take()
            if (value == -1) return decoded()
            consume(value)
        }
        return decoded()
    }

    private fun consume(value: Int) {
        when (value) {
            '\n'.code -> {
                terminated = true
                ending = PerfLineEnding.LF
            }
            '\r'.code -> carriageReturn()
            else -> bytes.write(value)
        }
    }

    private fun carriageReturn() {
        terminated = true
        ending = PerfLineEnding.CR
        if (position < limit) {
            val after = input.read()
            if (after == '\n'.code) {
                position++
                ending = PerfLineEnding.CRLF
            } else {
                pending = after
            }
        } else {
            trailingCr = true
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

    private fun take(): Int {
        val value = if (pending >= 0) pending.also { pending = -1 } else input.read()
        if (value >= 0) position++
        return value
    }
}

private enum class PerfLineEnding { NONE, LF, CR, CRLF }
