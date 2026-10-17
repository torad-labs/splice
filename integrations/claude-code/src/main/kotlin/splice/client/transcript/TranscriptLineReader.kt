// NEW: positioned JSONL framing in blocks, without synchronized calls or a fresh builder per byte or line.
package splice.client.transcript

import java.io.ByteArrayOutputStream
import java.io.InputStream

// A page and its metadata index agree on which oversized records are skipped.
internal const val TRANSCRIPT_LINE_BYTES: Int = 32 shl 20

// One bounded I/O block, reused for every line.
private const val TRANSCRIPT_READ_BLOCK = 64 shl 10

internal data class TranscriptLine(val offset: Long, val next: Long, val bytes: ByteArray?)

/** Reads only [until]'s snapshot, retaining at most one bounded line and one fixed-size input block. */
internal class TranscriptLineReader(private val input: InputStream, from: Long, private val until: Long) {
    private val block = ByteArray(TRANSCRIPT_READ_BLOCK)
    private val line = ByteArrayOutputStream()
    private var at = 0
    private var size = 0
    private var loaded = from
    private var offset = from
    private var oversized = false

    fun next(): TranscriptLine? {
        val start = offset
        line.reset()
        oversized = false
        while (true) {
            if (at == size && !fill()) {
                return if (offset == start) null else result(start)
            }
            var stop = at
            while (stop < size && block[stop] != '\n'.code.toByte()) stop++
            append(stop - at)
            if (stop < size) {
                at++
                offset++
                return result(start)
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

    private fun append(count: Int) {
        if (line.size().toLong() + count > TRANSCRIPT_LINE_BYTES) oversized = true
        if (!oversized) line.write(block, at, count)
        at += count
        offset += count
    }

    private fun result(start: Long): TranscriptLine =
        TranscriptLine(start, offset, if (oversized) null else line.toByteArray())
}
