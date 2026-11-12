// NEW: V4-343 — eight bytes tested at once, for the scans that walk every byte of a trace store: the backward
// read looking for the line before, and the trace count's walk through a record's strings. A test answers with
// a mask, 0x80 set in the bytes it found: whether any byte was found is exact, and so is the FIRST byte found,
// the lowest in the word, because a subtraction's borrow only runs upward from a found byte. A byte above the
// first found one may be flagged falsely, so a scan that wants the last one looks at those eight bytes itself.
package splice.core.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

// why: a one in the low bit of every byte of a word, the unit each word test is built from
private const val EVERY_BYTE = 0x0101010101010101L

/** Byte tests over words: eight bytes read as one Long, the first byte lowest. */
public object ByteWords {
    private val highBits = EVERY_BYTE shl (Byte.SIZE_BITS - 1)

    /** A view of [bytes] whose getLong(i) is the word of bytes i to i + 7, bytes[i] its lowest byte. */
    public fun view(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /** A word holding [b] in each of its bytes: what [equal] and [below] test against. */
    public fun repeated(b: Byte): Long = EVERY_BYTE * b.toUByte().toLong()

    /** The bytes of [word] that are the byte [pattern] repeats. */
    public fun equal(word: Long, pattern: Long): Long = zeros(word xor pattern)

    /** The bytes of [word], read unsigned, below the byte [pattern] repeats, which is at most 0x80. */
    public fun below(word: Long, pattern: Long): Long = (word - pattern) and word.inv() and highBits

    /** The offset in its word of the first byte [mask] found, the lowest; [mask] must have found one. */
    public fun first(mask: Long): Int = mask.countTrailingZeroBits() / Byte.SIZE_BITS

    private fun zeros(v: Long): Long = (v - EVERY_BYTE) and v.inv() and highBits
}
