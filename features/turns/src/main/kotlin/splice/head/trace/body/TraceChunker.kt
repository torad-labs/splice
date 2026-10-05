// NEW: V4-457 streaming Gear chunks and exact JSON body-literal encoding.
// 2026-10-05: chunks target 8 KiB. A client edits each body in several places per request (the cache marker
// moves, new messages land before the system prompt and tools), and the chunk around each edit is stored again
// whole: at a 32 KiB target that was most of what filled the day's pack.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.upstream.memory.JvmHeap
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.security.MessageDigest

// why: Gear boundaries target 8 KiB, bounded below at 4 KiB and above at 64 KiB. Re-chunking the 2026-10-05
// packs at 4/8/64 KiB with zstd stored 113 MiB where 16/32/64 KiB raw stored 1023 MiB (claudex).
internal const val CHUNK_MIN: Int = 4 * 1024

// why: 8 KiB is the expected Gear boundary spacing, so an edit re-stores about 8 KiB rather than about 32 KiB.
internal const val CHUNK_TARGET: Int = 8 * 1024

// why: cap one pack entry and one streaming buffer at 64 KiB regardless of input content.
internal const val CHUNK_MAX: Int = 64 * 1024

// why: one deterministic Gear value for each possible input byte; SHA-256 makes the table platform independent.
private val GEAR = LongArray(1 shl Byte.SIZE_BITS) { byte ->
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(byte.toByte()))).long
}

internal class TraceChunker(
    private val pack: TraceBodyPack,
    private val heap: HeapBudget = JvmHeap.budget,
) : OutputStream() {
    private val bufferLease = heap.reserve(CHUNK_MAX.toLong()) ?: throw HeapCapacityException()
    private val buffer = ByteArray(CHUNK_MAX).also { HeapOwners.keep(it, bufferLease) }
    private val parts = ArrayList<JsonObject>()
    private val partsLease = HeapOwners.charge(parts, heap, 0L)
    private var count = 0
    private var gear = 0L

    override fun write(value: Int) {
        buffer[count++] = value.toByte()
        gear = (gear shl 1) + GEAR[value.toUByte().toInt()]
        val boundary = count >= CHUNK_MIN && (gear and (CHUNK_TARGET - 1).toLong()) == 0L
        if (boundary || count == CHUNK_MAX) emit()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) write(bytes[index].toUByte().toInt())
    }

    fun finish(): JsonArray {
        if (count > 0) emit()
        return JsonArray(parts)
    }

    private fun emit() {
        if (!partsLease.resize((parts.size + 1L) * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        val copy = heap.reserve(count.toLong()) ?: throw HeapCapacityException()
        copy.use { parts += pack.put(buffer.copyOf(count)) }
        count = 0
        gear = 0L
    }
}

// why: array-list references and growth slack for one body chunk, whose payload index is charged separately.
private const val TRACE_CHUNK_REFERENCE_BYTES = 64L

/** Stream one JSON string literal once; lone surrogate code units remain exact across a truncation boundary. */
internal object TraceLiteral {
    fun encode(text: String, sink: OutputStream, heap: HeapBudget = JvmHeap.budget) {
        val peak = heap.reserve(TRACE_LITERAL_SCRATCH_CHARS * 2L + TRACE_LITERAL_ENCODER_BYTES)
            ?: throw HeapCapacityException()
        peak.use { encoded(text, sink) }
    }

    private fun encoded(text: String, sink: OutputStream) {
        val scratch = CharArray(TRACE_LITERAL_SCRATCH_CHARS)
        val writer = OutputStreamWriter(sink, Charsets.UTF_8)
        writer.write('"'.code)
        var from = 0
        text.indices.forEach { index ->
            val char = text[index]
            val escaped = shortEscape(char)
            val unicode = needsUnicode(text, index)
            if (escaped != null || unicode) {
                if (index > from) span(writer, text, from, index, scratch)
                if (escaped != null) writer.write(escaped) else scalar(writer, char)
                from = index + 1
            }
        }
        if (from < text.length) span(writer, text, from, text.length, scratch)
        writer.write('"'.code)
        writer.flush()
    }

    private fun span(writer: Writer, text: String, from: Int, until: Int, scratch: CharArray) {
        var at = from
        while (at < until) {
            val end = minOf(until, at + scratch.size)
            text.toCharArray(scratch, 0, at, end)
            writer.write(scratch, 0, end - at)
            at = end
        }
    }

    private fun shortEscape(char: Char): String? = when (char) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '\b' -> "\\b"
        '\u000c' -> "\\f"
        else -> null
    }

    private fun scalar(writer: Writer, char: Char) {
        writer.write("\\u")
        writer.write(char.code.toString(TRACE_LITERAL_HEX_RADIX).padStart(TRACE_LITERAL_HEX_DIGITS, '0'))
    }

    private fun needsUnicode(text: String, index: Int): Boolean {
        val char = text[index]
        val orphan = char.isSurrogate() && !paired(text, index)
        return char < ' ' || orphan
    }

    private fun paired(text: String, index: Int): Boolean = if (text[index].isHighSurrogate()) {
        index + 1 < text.length && text[index + 1].isLowSurrogate()
    } else {
        index > 0 && text[index - 1].isHighSurrogate()
    }
}

// why: spans copy through a fixed 4K-character buffer, never an array the size of a body literal.
private const val TRACE_LITERAL_SCRATCH_CHARS = 4096

// why: the UTF-8 stream encoder's byte buffer and small writer state coexist with the scratch array.
private const val TRACE_LITERAL_ENCODER_BYTES = 16 * 1024L

// why: JSON's Unicode escape has four hexadecimal digits per UTF-16 code unit.
private const val TRACE_LITERAL_HEX_RADIX = 16

// why: RFC 8259 encodes one UTF-16 code unit as four hexadecimal digits.
private const val TRACE_LITERAL_HEX_DIGITS = 4
