// NEW: V4-457 streaming Gear chunks and exact JSON body-literal encoding.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.security.MessageDigest

// why: Gear boundaries target 32 KiB, bounded below at 16 KiB and above at 64 KiB.
internal const val CHUNK_MIN: Int = 16 * 1024

// why: 32 KiB is the expected Gear boundary spacing for unchanged-prefix deduplication.
internal const val CHUNK_TARGET: Int = 32 * 1024

// why: cap one pack entry and one streaming buffer at 64 KiB regardless of input content.
internal const val CHUNK_MAX: Int = 64 * 1024

// why: one deterministic Gear value for each possible input byte; SHA-256 makes the table platform independent.
private val GEAR = LongArray(1 shl Byte.SIZE_BITS) { byte ->
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(byte.toByte()))).long
}

internal class TraceChunker(private val pack: TraceBodyPack) : OutputStream() {
    private val buffer = ByteArray(CHUNK_MAX)
    private val parts = ArrayList<JsonObject>()
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
        parts += pack.put(buffer.copyOf(count))
        count = 0
        gear = 0L
    }
}

/** Stream one JSON string literal once; lone surrogate code units remain exact across a truncation boundary. */
internal object TraceLiteral {
    fun encode(text: String, sink: OutputStream) {
        val writer = OutputStreamWriter(sink, Charsets.UTF_8)
        writer.write('"'.code)
        var from = 0
        text.indices.forEach { index ->
            val char = text[index]
            val escaped = shortEscape(char)
            val unicode = needsUnicode(text, index)
            if (escaped != null || unicode) {
                if (index > from) writer.write(text, from, index - from)
                if (escaped != null) writer.write(escaped) else scalar(writer, char)
                from = index + 1
            }
        }
        if (from < text.length) writer.write(text, from, text.length - from)
        writer.write('"'.code)
        writer.flush()
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

// why: JSON's Unicode escape has four hexadecimal digits per UTF-16 code unit.
private const val TRACE_LITERAL_HEX_RADIX = 16

// why: RFC 8259 encodes one UTF-16 code unit as four hexadecimal digits.
private const val TRACE_LITERAL_HEX_DIGITS = 4
