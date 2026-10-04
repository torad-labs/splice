// NEW: kernel socket rows are matched in reusable bytes before any row strings or keys exist.
package splice.upstream.transport

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteOrder
import java.nio.file.Path

internal fun interface ProcTableRead {
    fun open(table: Path): InputStream
}

// why: only the first five columns are needed; even tcp6 puts them within this fixed prefix.
private const val PROC_ROW_PREFIX_BYTES = 512

// why: sl, local, remote, state, tx_queue:rx_queue, counted from zero.
private const val PROC_FIELDS = 5
private const val PROC_LOCAL = 1
private const val PROC_REMOTE = 2
private const val PROC_QUEUES = 4

// why: one kernel address word is four bytes, printed as eight hex digits.
private const val PROC_WORD_BYTES = 4

// why: Linux writes IPv4 and IPv6 as 4 and 16 bytes, respectively.
private const val PROC_IPV4_BYTES = 4
private const val PROC_IPV6_BYTES = 16

// why: hexadecimal digits, and the ten zero bytes then two 0xff in an IPv4-mapped IPv6 address.
private const val PROC_HEX = 16
private const val PROC_MAPPED_ZEROS = 10
private const val PROC_BYTE_MAX = 255

internal class ProcSocketRows(keys: Set<SocketKey>) {
    private val tracked = keys.map { Tracked(it, End(it.local), End(it.remote)) }.toTypedArray()
    private val line = ByteArray(PROC_ROW_PREFIX_BYTES)
    private val starts = IntArray(PROC_FIELDS)
    private val ends = IntArray(PROC_FIELDS)
    private val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
    var allocatedRows: Int = 0
        private set

    fun read(input: InputStream): Map<SocketKey, Long> {
        val rows = HashMap<SocketKey, Long>()
        val stream = BufferedInputStream(input)
        var size = 0
        var header = true
        while (true) {
            val byte = stream.read()
            if (byte < 0) {
                if (!header && size > 0) row(size, rows)
                break
            }
            if (byte == '\n'.code) {
                if (!header) row(size, rows)
                header = false
                size = 0
            } else if (size < line.size) {
                line[size++] = byte.toByte()
            }
        }
        return rows
    }

    private fun row(size: Int, rows: MutableMap<SocketKey, Long>) {
        if (!fields(size)) return
        val key = trackedKey() ?: return
        val queueEnd = colon(starts[PROC_QUEUES], ends[PROC_QUEUES])
        val queued = hex(starts[PROC_QUEUES], queueEnd)
        if (!queued.valid) return
        allocatedRows++
        rows[key] = queued.value
    }

    private fun fields(size: Int): Boolean {
        var at = 0
        var field = 0
        while (at < size && field < PROC_FIELDS) {
            while (at < size && line[at] <= ' '.code) at++
            if (at == size) return false
            starts[field] = at
            while (at < size && line[at] > ' '.code) at++
            ends[field++] = at
        }
        return field == PROC_FIELDS
    }

    private fun trackedKey(): SocketKey? {
        var index = 0
        while (index < tracked.size) {
            val socket = tracked[index++]
            if (matches(socket.local, starts[PROC_LOCAL], ends[PROC_LOCAL]) &&
                matches(socket.remote, starts[PROC_REMOTE], ends[PROC_REMOTE])
            ) {
                return socket.key
            }
        }
        return null
    }

    private fun matches(end: End, from: Int, until: Int): Boolean {
        val cut = colon(from, until)
        val chars = cut - from
        if (chars != 2 * PROC_IPV4_BYTES && chars != 2 * PROC_IPV6_BYTES) return false
        val offset = addressOffset(end, from, chars / 2) ?: return false
        return hex(cut + 1, until).value == end.port.toLong() && sameAddress(end, from, offset)
    }

    private fun sameAddress(end: End, from: Int, offset: Int): Boolean {
        var byte = 0
        while (byte < end.bytes) {
            val expected = end.byte(byte)
            if (expected < 0 || kernelByte(from, offset + byte) != expected) return false
            byte++
        }
        return true
    }

    private fun addressOffset(end: End, from: Int, bytes: Int): Int? {
        if (bytes == end.bytes) return 0
        if (bytes != PROC_IPV6_BYTES || end.bytes != PROC_IPV4_BYTES) return null
        return if (mapped(from)) PROC_IPV6_BYTES - PROC_IPV4_BYTES else null
    }

    private fun mapped(from: Int): Boolean {
        var byte = 0
        while (byte < PROC_IPV6_BYTES - PROC_IPV4_BYTES) {
            val expected = if (byte < PROC_MAPPED_ZEROS) 0 else PROC_BYTE_MAX
            if (kernelByte(from, byte) != expected) return false
            byte++
        }
        return true
    }

    private fun kernelByte(from: Int, byte: Int): Int {
        val word = byte / PROC_WORD_BYTES
        val within = byte % PROC_WORD_BYTES
        val position = from + 2 * (word * PROC_WORD_BYTES + if (little) PROC_WORD_BYTES - 1 - within else within)
        val high = Character.digit(line[position].toInt(), PROC_HEX)
        val low = Character.digit(line[position + 1].toInt(), PROC_HEX)
        return if (high < 0 || low < 0) -1 else high * PROC_HEX + low
    }

    private fun colon(from: Int, until: Int): Int {
        var at = from
        while (at < until && line[at] != ':'.code.toByte()) at++
        return at
    }

    private fun hex(from: Int, until: Int): ProcNumber {
        if (from >= until) return ProcNumber(-1L)
        var value = 0L
        var at = from
        while (at < until) {
            val digit = Character.digit(line[at++].toInt(), PROC_HEX)
            if (digit < 0 || value > (Long.MAX_VALUE - digit) / PROC_HEX) return ProcNumber(-1L)
            value = value * PROC_HEX + digit
        }
        return ProcNumber(value)
    }

    private class End(val text: String) {
        private val cut = text.indexOf(':')
        val bytes = cut / 2
        val port = text.substring(cut + 1).toInt()
        fun byte(at: Int): Int {
            val high = Character.digit(text[at * 2], PROC_HEX)
            val low = Character.digit(text[at * 2 + 1], PROC_HEX)
            return if (high < 0 || low < 0) -1 else high * PROC_HEX + low
        }
    }

    private data class Tracked(val key: SocketKey, val local: End, val remote: End)
}

/** A nonnegative kernel number or malformed bytes, kept unboxed even on an untracked row. */
@JvmInline
private value class ProcNumber(val value: Long) {
    val valid: Boolean get() = value >= 0
}
