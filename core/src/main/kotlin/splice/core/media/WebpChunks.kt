// NEW: (DR-155) the RIFF container walk, split from ImageHeaderProbe.kt when the concentration
// wall flagged that file. WebP is a chunk LIST and the dimension chunk need not be first — an ICC
// profile, EXIF or ANIM block ahead of it is ordinary encoder output — so the walk skips by each
// chunk's declared size (with RIFF's odd-size pad byte) instead of assuming a position.
//
// Two of DR-155's four shipped defects were here, and both came from trusting a declared size:
//   * a chunk claiming size 1 still had ten bytes read past its own end, answering with a canvas
//     borrowed from whatever followed — which FORWARDS an undersized image, the opposite direction
//     to the PNG overflow but the same lie;
//   * a chunk claiming 0xFFFFFFFF overflowed an Int cursor NEGATIVE, and the next iteration indexed
//     the array at a negative offset and threw, out of a parser whose stated law is that failure is
//     always null. That one could take a turn down rather than merely misreport it.
// Hence: the cursor is a Long, and every reader is bounded by its chunk's DECLARED size as well as
// by the array.
package splice.core.media

internal class WebpChunks(private val at: ImageBytes, private val magics: ImageMagics) {

    /**
     * The OUTER container declares its own size at bytes 4..7, counting everything after those
     * eight, and [end] is where this file says it stops. A chunk beyond that point is not inside
     * this image however present its bytes happen to be.
     *
     * Missed in the first hardening pass, and the miss is instructive: the declared-size rule was
     * applied to every CHUNK and not to the container holding them, while the header of
     * ImageHeaderProbe.kt claimed it had been applied "to PNG, JPEG and RIFF alike". So a RIFF
     * declaring size 4 — the WEBP form word and nothing else — still had a VP8X read out of the
     * bytes that followed it, and the probe answered a confident 8x8 for a file that declared it
     * had ended. codex-splice found it by replaying the ownership rule against every reader instead
     * of the ones the note named, and grok-splice reproduced it independently.
     *
     * min() and not a length check, deliberately: a truncated download declares MORE than it holds
     * and must still be readable from its header, so the array bound stays the other half of the
     * pair. Only a file claiming to be SHORTER than its bytes loses anything here, and that file is
     * lying about one of the two.
     */
    fun read(b: ByteArray): ImagePx? {
        val end = minOf(b.size.toLong(), RIFF_HEADER + at.leU32(b, RIFF_SIZE))
        var cursor = RIFF_BODY.toLong()
        var found: ImagePx? = null
        while (found == null && cursor + CHUNK_HEADER < end) {
            val start = cursor.toInt()
            val size = at.leU32(b, start + TAG_LEN)
            // Before parsing, not after: a chunk that declares it owns nothing owns nothing, and a
            // zero size also cannot advance the cursor, so a file full of them would spin here.
            if (size <= 0) break
            found = chunk(b, at.ascii(b, start, TAG_LEN), start + CHUNK_HEADER, size, end)
            cursor += CHUNK_HEADER + size + (size and 1L)
        }
        return found
    }

    private fun chunk(b: ByteArray, tag: String, body: Int, size: Long, end: Long): ImagePx? = when (tag) {
        "VP8X" -> vp8x(b, body, size, end)
        "VP8L" -> vp8l(b, body, size, end)
        "VP8 " -> vp8(b, body, size, end)
        else -> null
    }

    // The extended header stores canvas size MINUS ONE, so a 1x1 image is encoded as zeroes — the
    // one format here where forgetting the +1 turns every image into a below-floor drop.
    private fun vp8x(b: ByteArray, body: Int, size: Long, end: Long): ImagePx? {
        if (!owns(body, size, VP8X_MIN, end)) return null
        return at.px(at.leU24(b, body + VP8X_W) + 1, at.leU24(b, body + VP8X_H) + 1, "webp")
    }

    // Lossless: a 0x2F signature then 14 bits of (width-1) and 14 bits of (height-1), packed.
    private fun vp8l(b: ByteArray, body: Int, size: Long, end: Long): ImagePx? {
        if (!owns(body, size, VP8L_MIN, end)) return null
        if (at.u8(b, body) != VP8L_SIGNATURE) return null
        val packed = at.leU32(b, body + 1)
        return at.px((packed and MASK_14) + 1, ((packed ushr BITS_14) and MASK_14) + 1, "webp")
    }

    // Lossy: the 3-byte frame tag, then the keyframe start code, then 14-bit dimensions. The start
    // code is checked because without it an interframe's tag bytes read as a plausible size.
    private fun vp8(b: ByteArray, body: Int, size: Long, end: Long): ImagePx? {
        if (!owns(body, size, VP8_MIN, end)) return null
        if (!at.startsWith(b, body + VP8_TAG, magics.vp8Keyframe)) return null
        val w = (at.leU16(b, body + VP8_W) and MASK_14_BITS).toLong()
        val h = (at.leU16(b, body + VP8_H) and MASK_14_BITS).toLong()
        return at.px(w, h, "webp")
    }

    /** A reader may only touch bytes its chunk DECLARES it owns AND that its CONTAINER declares it
     *  holds. Without the first half, a VP8X claiming size 1 still had ten bytes read out of
     *  whatever followed it and answered with total confidence; without the second, a chunk sitting
     *  past the end the RIFF header declared was read as if it were part of the file.
     *
     *  [end] is already the smaller of the container's declared end and the array, so it is the
     *  array bound too — one comparison rather than two that can disagree. */
    private fun owns(body: Int, size: Long, need: Int, end: Long): Boolean {
        val declared = size >= need
        return declared && body + need <= end
    }
}

private const val TAG_LEN = 4

// The RIFF header is the four-byte tag plus the four-byte size, and the size counts everything
// AFTER those eight — so the container's declared end is 8 + it.
private const val RIFF_SIZE = 4
private const val RIFF_HEADER = 8

private const val BITS_14 = 14
private const val MASK_14 = 0x3FFFL
private const val MASK_14_BITS = 0x3FFF

private const val RIFF_BODY = 12
private const val CHUNK_HEADER = 8
private const val VP8X_W = 4
private const val VP8X_H = 7
private const val VP8X_MIN = 10
private const val VP8L_SIGNATURE = 0x2F
private const val VP8L_MIN = 5
private const val VP8_TAG = 3
private const val VP8_W = 6
private const val VP8_H = 8
private const val VP8_MIN = 10
