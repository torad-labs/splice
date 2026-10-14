package splice.client.transcript

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapCategory
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import java.io.Reader

private const val CAPTURE_BUFFER_CHARS = 8_192
private const val CAPTURE_PARSER_BYTES = 1_024L
private const val CAPTURE_FRAME_BYTES = 64L
private const val CAPTURE_ASCII_END = 0x7f
private const val CAPTURE_TWO_BYTE_END = 0x7ff
private const val CAPTURE_THREE_BYTES = 3L

/** One privately leased character buffer. Positions stay in UTF-16 units so existing cursors and ids stay exact. */
internal class CapturedText(private val input: Reader, heap: HeapReservations?) : AutoCloseable {
    private val baseBytes = CAPTURE_BUFFER_CHARS * Char.SIZE_BYTES.toLong() + CAPTURE_PARSER_BYTES
    private val lease = heap?.let { HeapOwners.charge(this, it, baseBytes, HeapCategory.MATERIALIZATION) }
    private val buffer = CharArray(CAPTURE_BUFFER_CHARS)
    private var at = 0
    private var count = 0
    private var previousHigh = false
    private var marked: StringBuilder? = null
    private var maximum = 0
    var position: Int = 0
        private set
    var bytes: Long = 0
        private set

    fun peek(): Char? {
        if (count < 0) return null
        if (at == count) {
            count = input.read(buffer)
            at = 0
            if (count < 0) return null
        }
        return buffer[at]
    }

    fun take(): Char {
        val char = checkNotNull(peek()) { "incomplete captured JSON" }
        if (position == Int.MAX_VALUE) throw HeapCapacityException()
        position++
        at++
        bytes += width(char)
        previousHigh = char.isHighSurrogate()
        marked?.let {
            if (it.length < maximum) it.append(char) else marked = null
        }
        return char
    }

    private fun width(char: Char): Long = when {
        previousHigh && char.isLowSurrogate() -> CAPTURE_THREE_BYTES
        char.code <= CAPTURE_ASCII_END || char.isSurrogate() -> 1L
        char.code <= CAPTURE_TWO_BYTE_END -> 2L
        else -> CAPTURE_THREE_BYTES
    }

    fun seek(position: Int) {
        require(position >= this.position)
        while (this.position < position) take()
    }

    fun mark(maximum: Int) {
        this.maximum = maximum
        marked = StringBuilder(maximum)
    }

    fun marked(): String? = marked?.toString().also { marked = null }

    fun nesting(depth: Int) {
        if (lease?.resize(baseBytes + depth * CAPTURE_FRAME_BYTES) == false) throw HeapCapacityException()
    }

    override fun close() {
        Cancellables.withCleanup({ lease?.close() }) { input.close() }
    }
}
