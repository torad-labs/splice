// NEW: hashing and JSON streams share bounded UTF-8 scratch without copying complete strings.
package splice.core.util

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

// why: an 8 KiB streaming chunk keeps Unicode byte and character scratch below the allocation budget.
internal const val WIRE_BUFFER_BYTES = 8_192

/** Encodes borrowed strings with the JVM's replacement for malformed surrogates. Not thread-safe. */
public class Utf8Wire(private val output: OutputStream) {
    private var buffer = ByteBuffer.allocate(WIRE_BUFFER_BYTES)
    private var characters: CharBuffer? = null
    private val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    public fun write(text: String) {
        span(text, 0, text.length)
    }

    internal fun span(text: String, start: Int, end: Int) {
        var position = start
        // Short JSON keys and escaped spans need no CharBuffer or encoder reset.
        while (position < end && text[position].code < UTF8_ASCII_CEILING) {
            ascii(text[position++])
        }
        if (position == end) return
        val input = characters ?: CharBuffer.allocate(WIRE_BUFFER_BYTES).also { characters = it }
        input.clear()
        val encoding = encoder.reset()
        while (position < end) {
            val width = minOf(input.remaining(), end - position)
            text.toCharArray(input.array(), input.position(), position, position + width)
            input.position(input.position() + width)
            position += width
            input.flip()
            while (encoding.encode(input, buffer, position == end).isOverflow) drain()
            // Preserve a trailing high surrogate for the next borrowed slice, never replace it early.
            input.compact()
        }
        while (encoding.flush(buffer).isOverflow) drain()
    }

    internal fun ascii(character: Char) {
        if (!buffer.hasRemaining()) drain()
        buffer = buffer.put(character.code.toByte())
    }

    /** Writes buffered bytes without flushing or closing the caller-owned stream. */
    public fun drain() {
        if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
        buffer = buffer.clear()
    }
}
