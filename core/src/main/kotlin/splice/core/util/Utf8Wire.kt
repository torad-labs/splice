// NEW: hashing and JSON streams share bounded UTF-8 scratch without copying complete strings.
package splice.core.util

import java.io.OutputStream

// why: an 8 KiB streaming chunk keeps Unicode byte and character scratch below the allocation budget.
internal const val WIRE_BUFFER_BYTES = 8_192

// why: a first character scratch of 128 covers keys and scalars; it doubles toward one chunk only for longer text.
private const val CHAR_SCRATCH_FIRST = 128

// why: each UTF-8 trailing byte is 10xxxxxx, six payload bits under its continuation marker.
private const val UTF8_PAYLOAD_BITS = 6

// why: a continuation byte carries the code point's low six bits.
private const val UTF8_PAYLOAD_MASK = 0x3f

// why: the leading 10 bits identify a UTF-8 continuation byte.
private const val UTF8_CONTINUATION = 0x80

// why: the lead byte of a two, three and four byte sequence is 110xxxxx, 1110xxxx and 11110xxx.
private const val UTF8_TWO_BYTE_LEAD = 0xc0

// why: 1110 opens a three-byte UTF-8 sequence.
private const val UTF8_THREE_BYTE_LEAD = 0xe0

// why: 11110 opens a four-byte UTF-8 sequence.
private const val UTF8_FOUR_BYTE_LEAD = 0xf0

// why: the JVM's UTF-8 encoder (CharsetEncoder REPLACE, String.getBytes) writes this for an unpaired surrogate.
private const val UTF8_REPLACEMENT = '?'

/** Writes borrowed strings as the JVM's UTF-8 encoder does, including its one-byte replacement for an unpaired
 *  surrogate, in one pass over bulk-copied characters. Not thread-safe.
 *  why: the JDK encoder behind a per-character String.charAt prefix loop cost several times the SHA-256 it fed. */
public class Utf8Wire(private val output: OutputStream) {
    private val bytes = ByteArray(WIRE_BUFFER_BYTES)
    private var filled = 0
    private var characters: CharArray? = null

    public fun write(text: String) {
        span(text, 0, text.length)
    }

    /** [escapes] holds the bytes written instead of an ASCII code, by code; null writes every character itself. */
    internal fun span(text: String, start: Int, end: Int, escapes: Array<ByteArray?>? = null) {
        val chars = scratch(end - start)
        var position = start
        while (position < end) {
            var width = minOf(chars.size, end - position)
            // A pair this chunk cuts is joined in the next one; only the span's own end leaves a surrogate unpaired.
            if (position + width < end && text[position + width - 1].isHighSurrogate()) width--
            text.toCharArray(chars, 0, position, position + width)
            encode(chars, width, escapes)
            position += width
        }
    }

    internal fun ascii(character: Char) {
        if (filled == bytes.size) drain()
        bytes[filled++] = character.code.toByte()
    }

    /** Writes buffered bytes without flushing or closing the caller-owned stream. */
    public fun drain() {
        if (filled > 0) output.write(bytes, 0, filled)
        filled = 0
    }

    private fun scratch(length: Int): CharArray {
        val held = characters
        val wanted = minOf(length, WIRE_BUFFER_BYTES)
        if (held != null && held.size >= wanted) return held
        val grown = if (held == null) CHAR_SCRATCH_FIRST else 2 * held.size
        return CharArray(minOf(WIRE_BUFFER_BYTES, maxOf(wanted, grown))).also { characters = it }
    }

    /** ASCII as itself or its escape spelling, everything else as its UTF-8 sequence. */
    private fun encode(chars: CharArray, count: Int, escapes: Array<ByteArray?>?) {
        var index = 0
        while (index < count) {
            if (bytes.size - filled < UTF8_SUPPLEMENTARY_WIDTH) drain()
            val code = chars[index].code
            if (code < UTF8_ASCII_CEILING) {
                val escape = escapes?.get(code)
                if (escape == null) bytes[filled++] = code.toByte() else spelled(escape)
                index++
            } else {
                index = wide(chars, index, count)
            }
        }
    }

    private fun spelled(escape: ByteArray) {
        if (bytes.size - filled < escape.size) drain()
        System.arraycopy(escape, 0, bytes, filled, escape.size)
        filled += escape.size
    }

    /** One non-ASCII character, or the surrogate pair it starts, at [index]; returns the index after it. */
    private fun wide(chars: CharArray, index: Int, count: Int): Int {
        val code = chars[index].code
        val paired = chars[index].isHighSurrogate() && lowFollows(chars, index, count)
        when {
            code < UTF8_TWO_BYTE_CEILING -> sequence(code, 2, UTF8_TWO_BYTE_LEAD)
            paired -> sequence(
                Character.toCodePoint(chars[index], chars[index + 1]),
                UTF8_SUPPLEMENTARY_WIDTH,
                UTF8_FOUR_BYTE_LEAD,
            )
            chars[index].isSurrogate() -> bytes[filled++] = UTF8_REPLACEMENT.code.toByte()
            else -> sequence(code, UTF8_BMP_WIDTH, UTF8_THREE_BYTE_LEAD)
        }
        return if (paired) index + 2 else index + 1
    }

    /** Only the first [count] characters are this chunk's; the scratch past them is stale. */
    private fun lowFollows(chars: CharArray, index: Int, count: Int): Boolean =
        index + 1 < count && chars[index + 1].isLowSurrogate()

    /** The lead byte carries the bits above the trailing bytes' six each, highest first. */
    private fun sequence(point: Int, width: Int, lead: Int) {
        var trailing = width - 1
        bytes[filled++] = (lead or (point shr (trailing * UTF8_PAYLOAD_BITS))).toByte()
        while (trailing > 0) {
            trailing--
            val payload = (point shr (trailing * UTF8_PAYLOAD_BITS)) and UTF8_PAYLOAD_MASK
            bytes[filled++] = (UTF8_CONTINUATION or payload).toByte()
        }
    }
}
