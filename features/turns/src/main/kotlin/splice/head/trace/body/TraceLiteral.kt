// NEW: exact streaming JSON body-literal encoding, independent of Gear chunk partitioning.
package splice.head.trace.body

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.upstream.memory.JvmHeap
import java.io.OutputStream

/** Stream one JSON string literal once; lone surrogate code units remain exact across a truncation boundary. */
internal object TraceLiteral {
    /** Encodes [text] from char [from]; from 0 the literal opens with its quote, past it the quote is already out.
     *  A chunker's [cuts] hear the char each batch of bytes opens at. */
    fun encode(
        text: String,
        sink: OutputStream,
        heap: HeapReservations = JvmHeap.budget,
        from: Int = 0,
        cuts: TraceCuts? = null,
    ) {
        val peak = heap.reserve(TRACE_LITERAL_WRITER_BYTES) ?: throw HeapCapacityException()
        peak.use { Writer(text, sink, cuts).write(from) }
    }

    /** How many bytes char [index] of [text] encodes to; a surrogate pair counts all four at its first half. */
    fun width(text: String, index: Int): Int {
        val char = text[index]
        return when {
            shortEscape(char) != null -> SHORT_ESCAPE_BYTES
            needsUnicode(text, index) -> UNICODE_ESCAPE_BYTES
            char.isLowSurrogate() -> 0
            char.isHighSurrogate() -> UTF8_PAIR_BYTES
            char.code < UTF8_ONE_BYTE_LIMIT -> 1
            char.code < UTF8_TWO_BYTE_LIMIT -> 2
            else -> UTF8_THREE_BYTES
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

    /** One pass over the text: chars are copied out a block at a time and encoded straight to UTF-8 bytes, which
     *  reach the sink a batch at a time. Each char gets exactly the bytes [width] counts for it. */
    private class Writer(private val text: String, private val sink: OutputStream, private val cuts: TraceCuts?) {
        private val chars = CharArray(TRACE_LITERAL_BLOCK_CHARS)

        // A batch fills to TRACE_LITERAL_BATCH_BYTES; one more char's escape and the closing quote fit past it.
        private val batch = ByteArray(TRACE_LITERAL_BATCH_BYTES + UNICODE_ESCAPE_BYTES + 1)
        private var count = 0
        private var fed = 0L

        fun write(start: Int) {
            if (start == 0) batch[count++] = '"'.code.toByte()
            cuts?.origin(start, count.toLong())
            var at = start
            // A start on a pair's second half has no bytes of its own (the first half carried the pair). A UTF-8
            // encoder writes its replacement '?' for that half alone, and so does this. A resume never starts there.
            val insidePair = at < text.length && text[at].isLowSurrogate() && paired(text, at)
            if (insidePair) {
                batch[count++] = '?'.code.toByte()
                at++
            }
            while (at < text.length) at = block(at)
            batch[count++] = '"'.code.toByte()
            flush()
            sink.flush()
        }

        /** Encodes the block of chars from [from] and returns where the next block starts. */
        private fun block(from: Int): Int {
            var end = minOf(text.length, from + chars.size)
            // A block never ends between a pair's halves, so a half always meets its partner inside one block.
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            text.toCharArray(chars, 0, from, end)
            val size = end - from
            var k = 0
            while (k < size) {
                if (count >= TRACE_LITERAL_BATCH_BYTES) next(from + k)
                val stop = minOf(size, k + TRACE_LITERAL_BATCH_BYTES - count)
                var n = count
                while (k < stop && plain(chars[k])) batch[n++] = chars[k++].code.toByte()
                count = n
                if (k < stop) k = special(k, size)
            }
            return end
        }

        /** One byte with no escape: printable ASCII other than the quote and the backslash. */
        private fun plain(char: Char): Boolean = char >= ' ' && char < '\u0080' && char != '"' && char != '\\'

        /** Writes block char [k], which is not [plain], and returns the block index after it. */
        private fun special(k: Int, size: Int): Int {
            val char = chars[k]
            when {
                char.code < UTF8_ONE_BYTE_LIMIT -> escape(char)
                char.code < UTF8_TWO_BYTE_LIMIT -> utf8(char.code, TRACE_UTF8_TWO_LEAD, 1)
                !char.isSurrogate() -> utf8(char.code, TRACE_UTF8_THREE_LEAD, 2)
                else -> return surrogate(k, size)
            }
            return k + 1
        }

        /** A high half followed by a low half is one code point in four bytes; any other half is escaped alone. */
        private fun surrogate(k: Int, size: Int): Int {
            val char = chars[k]
            val pair = char.isHighSurrogate() && k + 1 < size && chars[k + 1].isLowSurrogate()
            if (!pair) {
                unicode(char.code)
                return k + 1
            }
            utf8(Character.toCodePoint(char, chars[k + 1]), TRACE_UTF8_FOUR_LEAD, TRACE_UTF8_FOUR_TAILS)
            return k + 2
        }

        /** An ASCII char JSON escapes: its short escape, or \u00XX for any other control char. */
        private fun escape(char: Char) {
            val short = shortEscape(char)
            if (short == null) {
                unicode(char.code)
            } else {
                batch[count++] = short[0].code.toByte()
                batch[count++] = short[1].code.toByte()
            }
        }

        /** A backslash, u, and the code unit's four lowercase hexadecimal digits. */
        private fun unicode(code: Int) {
            batch[count++] = '\\'.code.toByte()
            batch[count++] = 'u'.code.toByte()
            var shift = (TRACE_LITERAL_HEX_DIGITS - 1) * TRACE_LITERAL_HEX_BITS
            while (shift >= 0) {
                batch[count++] = TRACE_LITERAL_HEX[(code shr shift) and TRACE_LITERAL_HEX_MASK]
                shift -= TRACE_LITERAL_HEX_BITS
            }
        }

        /** [code] as one [lead] byte carrying its top bits, then [tails] continuation bytes of six bits each. */
        private fun utf8(code: Int, lead: Int, tails: Int) {
            var shift = tails * TRACE_UTF8_PAYLOAD_BITS
            batch[count++] = (lead or (code shr shift)).toByte()
            while (shift > 0) {
                shift -= TRACE_UTF8_PAYLOAD_BITS
                batch[count++] = (TRACE_UTF8_CONTINUATION or ((code shr shift) and TRACE_UTF8_PAYLOAD_MASK)).toByte()
            }
        }

        /** Feeds the batch to the sink; the next batch opens at text char [char]. */
        private fun next(char: Int) {
            flush()
            cuts?.origin(char, fed)
        }

        private fun flush() {
            sink.write(batch, 0, count)
            fed += count
            count = 0
        }
    }
}

// why: the writer copies 1K chars at a time out of the text, never an array the size of a body literal.
private const val TRACE_LITERAL_BLOCK_CHARS = 1024

// why: a cut lies within one batch of the char its batch opened at, so a mark re-measures at most 1 KiB of text.
private const val TRACE_LITERAL_BATCH_BYTES = 1024

// why: the 2 KiB char block, the 1 KiB byte batch and the writer's few fields, rounded up to 4 KiB.
private const val TRACE_LITERAL_WRITER_BYTES = 4 * 1024L

// why: JSON writers here have always escaped with lowercase hexadecimal digits.
private val TRACE_LITERAL_HEX = "0123456789abcdef".encodeToByteArray()

// why: RFC 8259 encodes one UTF-16 code unit as four hexadecimal digits.
private const val TRACE_LITERAL_HEX_DIGITS = 4

// why: one hexadecimal digit carries four bits.
private const val TRACE_LITERAL_HEX_BITS = 4

// why: the low four bits of a code unit are its last hexadecimal digit.
private const val TRACE_LITERAL_HEX_MASK = 0xf

// why: a UTF-8 continuation byte is 10 followed by six payload bits.
private const val TRACE_UTF8_CONTINUATION = 0x80

// why: each UTF-8 continuation byte carries six bits of the code point.
private const val TRACE_UTF8_PAYLOAD_BITS = 6

// why: the low six bits of a code point, one continuation byte's payload.
private const val TRACE_UTF8_PAYLOAD_MASK = 0x3f

// why: 110 opens a two-byte UTF-8 sequence.
private const val TRACE_UTF8_TWO_LEAD = 0xc0

// why: 1110 opens a three-byte UTF-8 sequence.
private const val TRACE_UTF8_THREE_LEAD = 0xe0

// why: 11110 opens a four-byte UTF-8 sequence, a code point beyond U+FFFF.
private const val TRACE_UTF8_FOUR_LEAD = 0xf0

// why: a four-byte UTF-8 sequence has three continuation bytes after its lead.
private const val TRACE_UTF8_FOUR_TAILS = 3

// why: a short escape is a backslash and one letter.
private const val SHORT_ESCAPE_BYTES = 2

// why: a Unicode escape is a backslash, u, and four hexadecimal digits.
internal const val UNICODE_ESCAPE_BYTES = 6

// why: a UTF-16 surrogate pair is one code point beyond U+FFFF, four bytes in UTF-8.
private const val UTF8_PAIR_BYTES = 4

// why: UTF-8 encodes code units below 0x80 in one byte.
private const val UTF8_ONE_BYTE_LIMIT = 0x80

// why: UTF-8 encodes code units below 0x800 in two bytes.
private const val UTF8_TWO_BYTE_LIMIT = 0x800

// why: every other code unit of the Basic Multilingual Plane is three bytes in UTF-8.
private const val UTF8_THREE_BYTES = 3
