// NEW: (DR-155) unsigned reads over a byte window, and the one sanity gate every format answer
// passes through. Split out of ImageHeaderProbe.kt when the concentration wall caught that file at
// ratio 3.37 — four concerns and 191 logic lines in one place, which is the god-object shape the
// wall exists to stop, and the split is the wall's own remedy rather than an exemption.
//
// The 32-bit reads return Long ON PURPOSE. A width is unsigned and does not fit an Int: read into
// one, 0x80000000 becomes Int.MIN_VALUE, and a floor check then sees a hugely negative "width" and
// drops the image as too small — splice DELETING a picture because a malformed header overflowed.
// That defect shipped; codex-splice found it by replaying the parser against hostile input.
package splice.core.media

/** Bounds are the CALLER's job on every accessor except [px] — each format reader knows both its
 *  array bound and the length its own segment declared, and only it can check both. */
internal class ImageBytes {
    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and BYTE_MASK

    fun ascii(b: ByteArray, at: Int, len: Int): String =
        if (at + len <= b.size) String(b, at, len, Charsets.US_ASCII) else ""

    fun beU16(b: ByteArray, at: Int): Int = (u8(b, at) shl BITS_8) or u8(b, at + 1)

    fun beU32(b: ByteArray, at: Int): Long =
        (0 until WORD_4).fold(0L) { acc, i -> (acc shl BITS_8) or u8(b, at + i).toLong() }

    fun leU16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl BITS_8)

    fun leU24(b: ByteArray, at: Int): Long = leBytes(b, at, WORD_3)

    fun leU32(b: ByteArray, at: Int): Long = leBytes(b, at, WORD_4)

    private fun leBytes(b: ByteArray, at: Int, count: Int): Long =
        (0 until count).fold(0L) { acc, i -> acc or (u8(b, at + i).toLong() shl (BITS_8 * i)) }

    fun startsWith(b: ByteArray, at: Int, magic: ByteArray): Boolean =
        at + magic.size <= b.size && magic.indices.all { b[at + it] == magic[it] }

    /**
     * Dimensions, or null when the header's own numbers are not a possible image.
     *
     * Zero and anything too large to be an Int are UNKNOWN, not small. That direction is the whole
     * policy: a malformed header must forward exactly as it does today, because the alternative is
     * splice silently deleting a picture on the strength of a number the file made up. An arm in
     * this suite asserted the opposite for zero until DR-155's review; it was wrong the same way.
     */
    fun px(w: Long, h: Long, format: String): ImagePx? {
        val possible = w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION
        return if (possible) ImagePx(w.toInt(), h.toInt(), format) else null
    }
}

private const val BYTE_MASK = 0xFF
private const val BITS_8 = 8
private const val WORD_3 = 3
private const val WORD_4 = 4

// A dimension too large to be an Int is a dimension this proxy cannot represent, and therefore one
// it does not know. No real image reaches it; a hostile header does.
private const val MAX_DIMENSION = Int.MAX_VALUE.toLong()
