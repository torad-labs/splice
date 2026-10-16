// NEW: V4-457 streaming Gear chunks and exact JSON body-literal encoding.
// 2026-10-05: chunks target 8 KiB. A client edits each body in several places per request (the cache marker
// moves, new messages land before the system prompt and tools), and the chunk around each edit is stored again
// whole: at a 32 KiB target that was most of what filled the day's pack.
// 2026-10-15: trace writing was 13% of the daemon's CPU (JFR, 90 s). A literal is now encoded in one pass straight to
// bytes, a chunk's opening bytes are copied without Gear, which cannot cut there, and each cut records where its text
// stood, so a remembered literal's marks no longer measure its whole text again. The bytes written are unchanged.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.upstream.memory.JvmHeap
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

// why: Gear boundaries target 8 KiB, bounded below at 4 KiB and above at 64 KiB. Re-chunking the 2026-10-05
// packs at 4/8/64 KiB with zstd stored 113 MiB where 16/32/64 KiB raw stored 1023 MiB (claudex).
internal const val CHUNK_MIN: Int = 4 * 1024

// why: 8 KiB is the expected Gear boundary spacing, so an edit re-stores about 8 KiB rather than about 32 KiB.
internal const val CHUNK_TARGET: Int = 8 * 1024

// why: cap one pack entry and one streaming buffer at 64 KiB regardless of input content.
internal const val CHUNK_MAX: Int = 64 * 1024

// why: Gear shifts its value one bit per byte, so 64 bytes later it holds nothing of a byte. No cut is tested before
// CHUNK_MIN, so a chunk's bytes before this offset never reach a test and are copied without hashing.
private const val TRACE_GEAR_FROM: Int = CHUNK_MIN - Long.SIZE_BITS

// why: one deterministic Gear value for each possible input byte; SHA-256 makes the table platform independent.
private val GEAR = LongArray(1 shl Byte.SIZE_BITS) { byte ->
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(byte.toByte()))).long
}

/** Cuts one literal's bytes into stored parts. A resumed literal starts with the [reused] parts it shares with a
 *  recent one and drops the first [skip] bytes it is fed, which already lie inside the last of them. */
internal class TraceChunker(
    private val pack: TraceBodyPack,
    private val heap: HeapReservations = JvmHeap.budget,
    reused: List<JsonObject> = emptyList(),
    private var skip: Int = 0,
) : OutputStream() {
    // The buffer and the cut record live and die with this chunker, so one lease holds both.
    private val bufferLease = heap.reserve(CHUNK_MAX + TRACE_CUTS_FIRST * TRACE_CUT_BYTES)
        ?: throw HeapCapacityException()
    private val buffer = ByteArray(CHUNK_MAX).also { HeapOwners.keep(it, bufferLease) }
    private val parts = ArrayList<JsonObject>()
    private val partsLease = HeapOwners.charge(parts, heap, 0L)
    private var count = 0
    private var gear = 0L

    /** Where each part this chunker cuts lies in its literal's text; the encoder names its chars here. */
    val cuts = TraceCuts(bufferLease, CHUNK_MAX.toLong())

    init {
        if (!partsLease.resize(reused.size * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        parts += reused
    }

    override fun write(value: Int) {
        if (skip > 0) {
            skip--
            return
        }
        buffer[count++] = value.toByte()
        gear = (gear shl 1) + GEAR[value.toUByte().toInt()]
        val boundary = count >= CHUNK_MIN && (gear and (CHUNK_TARGET - 1).toLong()) == 0L
        if (boundary || count == CHUNK_MAX) emit()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val dropped = minOf(skip, length)
        skip -= dropped
        val end = offset + length
        var index = offset + dropped
        while (index < end) {
            if (count < TRACE_GEAR_FROM) {
                // The Gear value at CHUNK_MIN depends only on the 64 bytes before it, so these skip the hash.
                val run = minOf(end - index, TRACE_GEAR_FROM - count)
                bytes.copyInto(buffer, count, index, index + run)
                count += run
                index += run
            } else {
                val first = bytes[index++].toUByte().toInt()
                if (index == end) write(first) else pair(first, bytes[index++].toUByte().toInt())
            }
        }
    }

    private fun pair(first: Int, second: Int) {
        val mask = (CHUNK_TARGET - 1).toLong()
        val firstGear = GEAR[first]
        val shiftedFirst = (gear shl 2) + (firstGear shl 1)
        buffer[count++] = first.toByte()
        // Shifting h1 drops its low bit, so the original first-byte cut also checks firstGear's parity.
        val firstBoundary = count >= CHUNK_MIN && (firstGear and 1L) == 0L &&
            (shiftedFirst and (mask shl 1)) == 0L
        if (firstBoundary || count == CHUNK_MAX) {
            gear = (gear shl 1) + firstGear
            emit()
            // The second byte belongs to a fresh chunk after emit resets Gear.
            write(second)
        } else {
            buffer[count++] = second.toByte()
            gear = shiftedFirst + GEAR[second]
            val secondBoundary = count >= CHUNK_MIN && (gear and mask) == 0L
            if (secondBoundary || count == CHUNK_MAX) emit()
        }
    }

    fun finish(): JsonArray {
        if (count > 0) emit()
        return JsonArray(parts)
    }

    private fun emit() {
        if (!partsLease.resize((parts.size + 1L) * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        cuts.cut(count)
        val copy = heap.reserve(count.toLong()) ?: throw HeapCapacityException()
        copy.use { parts += pack.put(buffer.copyOf(count)) }
        count = 0
        gear = 0L
    }
}

// why: array-list references and growth slack for one body chunk, whose payload index is charged separately.
private const val TRACE_CHUNK_REFERENCE_BYTES = 64L

/** For each part one chunker cut, reused parts not counted: its length, and the last text char the encoder named
 *  before the cut with the bytes it had fed before that char. Every char before that one lies inside the parts up
 *  to this cut, so a mark measures from it (TraceRecentLiterals). Growth is charged to [lease] above [base]. */
internal class TraceCuts(private val lease: HeapLease, private val base: Long) {
    private var chars = IntArray(TRACE_CUTS_FIRST)
    private var feds = LongArray(TRACE_CUTS_FIRST)
    private var lengths = IntArray(TRACE_CUTS_FIRST)
    private var size = 0
    private var namedChar = 0
    private var namedFed = 0L

    /** The encoder's bytes for text char [char] start [fed] bytes into what it feeds the chunker. */
    fun origin(char: Int, fed: Long) {
        namedChar = char
        namedFed = fed
    }

    fun cut(length: Int) {
        if (size == chars.size) grow()
        chars[size] = namedChar
        feds[size] = namedFed
        lengths[size] = length
        size++
    }

    fun originChar(cut: Int): Int = chars[cut]

    fun originFed(cut: Int): Long = feds[cut]

    fun length(cut: Int): Int = lengths[cut]

    private fun grow() {
        val slots = chars.size * 2
        if (!lease.resize(base + slots * TRACE_CUT_BYTES)) throw HeapCapacityException()
        chars = chars.copyOf(slots)
        feds = feds.copyOf(slots)
        lengths = lengths.copyOf(slots)
    }
}

// why: eight cuts hold a literal of about 96 KiB at the usual cut spacing, so most literals never grow the record.
private const val TRACE_CUTS_FIRST = 8

// why: a cut is two ints and a long, 16 bytes, and half again while a doubling copy holds the old arrays too.
private const val TRACE_CUT_BYTES = 24L

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
