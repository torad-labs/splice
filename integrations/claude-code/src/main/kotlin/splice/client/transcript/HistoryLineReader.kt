package splice.client.transcript

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

private const val HISTORY_READ_BLOCK = 64 shl 10

internal data class HistoryLine(val text: String, val next: Long, val complete: Boolean)

/** One fixed input block and one row, never the whole unread suffix. No oversized row is silently dropped. */
internal class HistoryLineReader(private val input: InputStream, from: Long, private val until: Long) {
    private val block = ByteArray(HISTORY_READ_BLOCK)
    private val row = ByteArrayOutputStream()
    private var at = 0
    private var size = 0
    private var loaded = from
    private var offset = from

    fun next(): HistoryLine? {
        val start = offset
        row.reset()
        while (true) {
            if (at == size && !fill()) {
                return if (offset == start) null else result(false)
            }
            var stop = at
            while (stop < size && block[stop] != '\n'.code.toByte()) stop++
            row.write(block, at, stop - at)
            offset += stop - at
            at = stop
            if (at < size) {
                at++
                offset++
                return result(true)
            }
        }
    }

    private fun fill(): Boolean {
        if (loaded >= until) return false
        size = input.read(block, 0, minOf(block.size.toLong(), until - loaded).toInt())
        at = 0
        if (size <= 0) return false
        loaded += size
        return true
    }

    private fun result(complete: Boolean): HistoryLine =
        HistoryLine(row.toString(StandardCharsets.UTF_8), offset, complete)
}
