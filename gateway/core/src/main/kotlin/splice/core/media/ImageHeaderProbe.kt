// NEW: (DR-155) read an image's DIMENSIONS out of its header, and nothing else. xAI rejects an
// undersized image with HTTP 400 invalid_image — "Image dimensions 1x1 are too small. Both width
// and height must be at least 8 pixels." — and splice forwarded whatever the client sent, so a
// tracking pixel or a collapsed screenshot killed the turn with a vendor error the operator could
// not act on. Six byte-identical bodies of exactly that shape are what the DR-152 soak captured.
//
// LAW (DR-155): this reads a HEADER WINDOW and never the payload. It returns (format, width,
// height) and nothing else can be recovered from it, so no caller can log, forward or persist image
// CONTENT through this seam. Failure is always null, and null means UNKNOWN — never "bad".
//
// PACKAGE (arch law, caught by the gate on the first attempt): splice.core.wire is for SERIALIZABLE
// wire DTOs and the arch test enforces that every class there carries @Serializable. This is
// behaviour, not a wire type — nothing here ever crosses a wire — so it lives in splice.core.media
// beside the policy that consumes it.
package splice.core.media

import java.util.Base64

/** What a header says about an image. [format] is the family the MAGIC identified, which is not
 *  necessarily what the client's declared media type claimed. */
public data class ImagePx(val width: Int, val height: Int, val format: String)

/** Big/little-endian reads over a byte window, split out so [ImageHeaderProbe] keeps its own
 *  function count for format readers. Every accessor is bounds-checked by its caller before use. */
internal class ImageBytes {
    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and BYTE_MASK

    fun ascii(b: ByteArray, at: Int, len: Int): String =
        if (at + len <= b.size) String(b, at, len, Charsets.US_ASCII) else ""

    fun beU16(b: ByteArray, at: Int): Int = (u8(b, at) shl BITS_8) or u8(b, at + 1)

    fun beU32(b: ByteArray, at: Int): Int =
        (0 until WORD_4).fold(0) { acc, i -> (acc shl BITS_8) or u8(b, at + i) }

    fun leU16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl BITS_8)

    fun leU24(b: ByteArray, at: Int): Int = leBytes(b, at, WORD_3)

    fun leU32(b: ByteArray, at: Int): Int = leBytes(b, at, WORD_4)

    private fun leBytes(b: ByteArray, at: Int, count: Int): Int =
        (0 until count).fold(0) { acc, i -> acc or (u8(b, at + i) shl (BITS_8 * i)) }

    fun startsWith(b: ByteArray, at: Int, magic: ByteArray): Boolean =
        at + magic.size <= b.size && magic.indices.all { b[at + it] == magic[it] }
}

/**
 * Header-only dimension reader for the four families a chat client actually sends.
 *
 * EVERY failure returns null, and null means UNKNOWN. A caller that dropped on null would be
 * dropping on "I could not tell", which is the opposite of what an undersized-image policy is for —
 * a truncated upload, an unrecognised family, or a codec added after this file was written must all
 * keep riding upstream exactly as they do today. The only decision this enables is "definitely
 * smaller than the floor".
 */
public class ImageHeaderProbe {

    private val at = ImageBytes()

    // Magics as LATIN-1 text rather than byte literals: ISO-8859-1 maps every char to the byte
    // of the same value, so each string below IS its signature, spelled the way the format's own
    // spec spells it, with the printable halves (PNG, GIF87a, RIFF) readable as themselves. The
    // non-printing bytes stay backslash-u escaped on purpose: a raw 0x1A or 0x01 sitting in a
    // source file is invisible in a diff, and the first editor to normalise it would silently
    // break the magic with nothing to see in review.
    private val pngMagic = latin1("\u0089PNG\r\n\u001A\n")
    private val gif87a = latin1("GIF87a")
    private val gif89a = latin1("GIF89a")
    private val jpegSoi = latin1("\u00FF\u00D8") // SOI
    private val riff = latin1("RIFF")
    private val vp8Start = latin1("\u009D\u0001\u002A") // VP8 keyframe start code

    private fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    /** Dimensions from [bytes], or null when they cannot be read with certainty. */
    public fun probe(bytes: ByteArray): ImagePx? {
        val isGif = at.startsWith(bytes, 0, gif87a) || at.startsWith(bytes, 0, gif89a)
        val isRiff = at.startsWith(bytes, 0, riff)
        return when {
            at.startsWith(bytes, 0, pngMagic) -> png(bytes)
            isGif -> gif(bytes)
            at.startsWith(bytes, 0, jpegSoi) -> jpeg(bytes)
            isRiff && at.ascii(bytes, RIFF_FORM, TAG_LEN) == "WEBP" -> webp(bytes)
            else -> null
        }
    }

    /**
     * The same, from base64 text. Only the HEADER WINDOW is decoded — a screenshot is megabytes and
     * every format here is settled inside the first few dozen bytes, so decoding the whole payload
     * to read two integers would be waste with a copy of the image on the heap as its only result.
     *
     * Invalid or truncated base64 returns null (unknown), never an exception and never a drop.
     */
    public fun probeBase64(data: String): ImagePx? {
        val packed = data.filterNot { it.isWhitespace() }
        val window = packed.take(HEADER_B64_CHARS)
        val aligned = window.take(window.length - window.length % B64_GROUP)
        return runCatching { Base64.getDecoder().decode(aligned) }.getOrNull()?.let { probe(it) }
    }

    // The IHDR tag is REQUIRED, not just the 8-byte magic: the first chunk's length field alone
    // would happily read four arbitrary bytes as a width on a file that merely starts like a PNG.
    private fun png(b: ByteArray): ImagePx? =
        if (b.size >= PNG_MIN && at.ascii(b, PNG_IHDR, TAG_LEN) == "IHDR") {
            ImagePx(at.beU32(b, PNG_W), at.beU32(b, PNG_H), "png")
        } else {
            null
        }

    private fun gif(b: ByteArray): ImagePx? =
        if (b.size >= GIF_MIN) ImagePx(at.leU16(b, GIF_W), at.leU16(b, GIF_H), "gif") else null

    // Walk the marker chain to a real frame header. Dimensions live on SOFn and nowhere else, so
    // APPn/DQT/DHT/COM are skipped by their own length, and the walk stops at SOS (entropy data
    // starts, and nothing past it is a marker) rather than guessing.
    private fun jpeg(b: ByteArray): ImagePx? {
        var i = jpegSoi.size
        var found: ImagePx? = null
        while (found == null && i + JPEG_STEP < b.size) {
            val marker = at.u8(b, i + 1)
            // Past EOI or SOS there is no chain left to walk: SOS begins the entropy-coded scan,
            // whose 0xFF bytes are compressed pixels, not markers.
            val walkable = at.u8(b, i) == MARKER_PREFIX && marker !in STOP_MARKERS
            if (!walkable) return null
            found = if (isSof(marker)) sofDims(b, i + MARKER_LEN) else null
            i = advance(b, i, marker) ?: return null
        }
        return found
    }

    /** Where the next marker begins, or null when the chain stops being readable from here. */
    private fun advance(b: ByteArray, marker0: Int, marker: Int): Int? {
        // A run of 0xFF is fill BEFORE the real marker, not a marker: step one byte, not two.
        if (marker == MARKER_PREFIX) return marker0 + 1
        val segment = marker0 + MARKER_LEN
        if (marker in STANDALONE_MARKERS) return segment
        val len = at.beU16(b, segment)
        return if (len < SEGMENT_MIN) null else segment + len
    }

    // 0xC0..0xCF are frame headers EXCEPT C4 (Huffman tables), C8 (JPEG extensions) and CC
    // (arithmetic conditioning), which share the range and carry no dimensions. Progressive (C2) is
    // a frame header like any other — a probe that only knew C0 would miss every progressive JPEG,
    // which is most of what a browser produces.
    private fun isSof(marker: Int): Boolean {
        val framish = marker in SOF_FIRST..SOF_LAST
        return framish && marker !in NOT_A_FRAME
    }

    private fun sofDims(b: ByteArray, seg: Int): ImagePx? =
        if (seg + SOF_MIN <= b.size) {
            ImagePx(at.beU16(b, seg + SOF_W), at.beU16(b, seg + SOF_H), "jpeg")
        } else {
            null
        }

    // RIFF is a chunk container and the dimension chunk need not be first — an ICC profile or EXIF
    // block ahead of it is ordinary encoder output, so the walk skips by declared size (with RIFF's
    // odd-size pad byte) instead of assuming position.
    private fun webp(b: ByteArray): ImagePx? {
        var i = RIFF_BODY
        var found: ImagePx? = null
        while (found == null && i + CHUNK_HEADER < b.size) {
            val size = at.leU32(b, i + TAG_LEN)
            found = webpChunk(b, at.ascii(b, i, TAG_LEN), i + CHUNK_HEADER)
            if (size <= 0) break
            i += CHUNK_HEADER + size + (size and 1)
        }
        return found
    }

    private fun webpChunk(b: ByteArray, tag: String, body: Int): ImagePx? = when (tag) {
        "VP8X" -> vp8x(b, body)
        "VP8L" -> vp8l(b, body)
        "VP8 " -> vp8(b, body)
        else -> null
    }

    // The extended header stores canvas size MINUS ONE, so a 1x1 image is encoded as zeroes — the
    // one format here where forgetting the +1 turns every image into a below-floor drop.
    private fun vp8x(b: ByteArray, body: Int): ImagePx? =
        if (body + VP8X_MIN <= b.size) {
            ImagePx(at.leU24(b, body + VP8X_W) + 1, at.leU24(b, body + VP8X_H) + 1, "webp")
        } else {
            null
        }

    // Lossless: a 0x2F signature then 14 bits of (width-1) and 14 bits of (height-1), packed.
    private fun vp8l(b: ByteArray, body: Int): ImagePx? {
        val readable = body + VP8L_MIN <= b.size
        return if (readable && at.u8(b, body) == VP8L_SIGNATURE) {
            val packed = at.leU32(b, body + 1)
            ImagePx((packed and MASK_14) + 1, ((packed ushr BITS_14) and MASK_14) + 1, "webp")
        } else {
            null
        }
    }

    // Lossy: the 3-byte frame tag, then the keyframe start code, then 14-bit dimensions. The start
    // code is checked because without it an interframe's tag bytes read as a plausible size.
    private fun vp8(b: ByteArray, body: Int): ImagePx? {
        val readable = body + VP8_MIN <= b.size
        return if (readable && at.startsWith(b, body + VP8_TAG, vp8Start)) {
            ImagePx(at.leU16(b, body + VP8_W) and MASK_14, at.leU16(b, body + VP8_H) and MASK_14, "webp")
        } else {
            null
        }
    }
}

private const val BYTE_MASK = 0xFF
private const val BITS_8 = 8
private const val BITS_14 = 14
private const val MASK_14 = 0x3FFF
private const val WORD_3 = 3
private const val WORD_4 = 4
private const val TAG_LEN = 4

// How much base64 to decode: 128 chars is 96 bytes, and the deepest header this file reads (a WebP
// VP8X canvas) is settled by byte 30.
private const val HEADER_B64_CHARS = 128
private const val B64_GROUP = 4

private const val PNG_IHDR = 12
private const val PNG_W = 16
private const val PNG_H = 20
private const val PNG_MIN = 24

private const val GIF_W = 6
private const val GIF_H = 8
private const val GIF_MIN = 10

private const val MARKER_PREFIX = 0xFF
private const val MARKER_LEN = 2
private const val TEM = 0x01
private const val SOI_MARKER = 0xD8
private const val RST_FIRST = 0xD0
private const val RST_LAST = 0xD7
private const val EOI_MARKER = 0xD9
private const val SOS_MARKER = 0xDA
private const val SOF_FIRST = 0xC0
private const val SOF_LAST = 0xCF
private const val JPEG_STEP = 3
private const val SEGMENT_MIN = 2
private const val SOF_H = 3
private const val SOF_W = 5
private const val SOF_MIN = 7

// Markers that carry no length payload at all: restart intervals, TEM, and a nested SOI.
private val STANDALONE_MARKERS = (RST_FIRST..RST_LAST).toSet() + TEM + SOI_MARKER

// Past either of these there are no more markers to walk — EOI ends the image, SOS begins the
// entropy-coded scan, whose bytes are not a marker chain.
private val STOP_MARKERS = setOf(EOI_MARKER, SOS_MARKER)

// Inside the SOF range, but not frame headers.
private const val DHT = 0xC4 // Huffman tables
private const val JPG_EXT = 0xC8 // reserved JPEG extensions
private const val DAC = 0xCC // arithmetic coding conditioning
private val NOT_A_FRAME = setOf(DHT, JPG_EXT, DAC)

private const val RIFF_FORM = 8
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
