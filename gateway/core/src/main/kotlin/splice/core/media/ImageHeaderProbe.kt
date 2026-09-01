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
// THE INPUT IS HOSTILE, and the first version of this file did not treat it that way. Five defects
// found by codex-splice's adversarial replay and grok-splice's sibling sweep, all one root cause:
// A NUMBER A HOSTILE FILE CHOSE IS AN INPUT, NOT A FACT. A 32-bit PNG width read into an Int went
// NEGATIVE and made splice DELETE a picture; a JPEG frame header declaring length 2 had its
// dimensions read out of the bytes that followed; a WebP chunk declaring size 1 did the same; a
// WebP chunk declaring 0xFFFFFFFF overflowed the walk cursor and THREW; and PNG ignored IHDR's own
// declared length, the same borrow in a third format. Note the two directions: the overflow was
// fail-CLOSED (a legal image dropped) and the chunk borrow fail-open (an undersized one forwarded),
// so an invented dimension lies both ways and neither is safe.
//
// The rule that replaced them: a reader may only touch bytes its segment DECLARES it owns and that
// are actually present, every multi-byte read is unsigned into a Long, and a number that is not a
// possible image is UNKNOWN rather than small.
//
// That sentence used to end "applied to PNG, JPEG and RIFF alike", and the RIFF third of it was
// FALSE when written: the rule reached every chunk and not the container holding them, so a RIFF
// declaring it ended after its form word still had a VP8X read out of the bytes past that end.
// Both reviewing seats found it independently by replaying the rule against every reader rather
// than against the ones this note named — which is the argument for stating a claim narrowly
// enough to be checked. The container bound now lives in WebpChunks.read, and the rule is applied
// where it says it is.
//
// PACKAGE (arch law, caught by the gate): splice.core.wire is for SERIALIZABLE wire DTOs and the
// arch test enforces that every class there carries @Serializable. This is behaviour, not a wire
// type — nothing here ever crosses a wire — so it lives in splice.core.media beside the policy that
// consumes it. The reader, the signatures and the RIFF walk are siblings in this package
// (ImageBytes.kt, ImageMagics.kt, WebpChunks.kt); the concentration wall split them out of here.
package splice.core.media

import java.util.Base64

/** What a header says about an image. [format] is the family the MAGIC identified, which is not
 *  necessarily what the client's declared media type claimed. */
public data class ImagePx(val width: Int, val height: Int, val format: String)

/**
 * Header-only dimension reader for the four families a chat client actually sends.
 *
 * EVERY failure returns null, and null means UNKNOWN. A caller that dropped on null would be
 * dropping on "I could not tell", which is the opposite of what an undersized-image policy is for —
 * a truncated upload, an unrecognised family, a malformed header, or a codec added after this file
 * was written must all keep riding upstream exactly as they do today. The only decision this
 * enables is "definitely smaller than the floor".
 */
public class ImageHeaderProbe {

    private val at = ImageBytes()
    private val magics = ImageMagics()
    private val webp = WebpChunks(at, magics)

    /** Dimensions from [bytes], or null when they cannot be read with certainty. */
    public fun probe(bytes: ByteArray): ImagePx? {
        val isGif = at.startsWith(bytes, 0, magics.gif87a) || at.startsWith(bytes, 0, magics.gif89a)
        val isRiff = at.startsWith(bytes, 0, magics.riff)
        return when {
            at.startsWith(bytes, 0, magics.png) -> png(bytes)
            isGif -> gif(bytes)
            at.startsWith(bytes, 0, magics.jpegSoi) -> jpeg(bytes)
            isRiff && at.ascii(bytes, RIFF_FORM, TAG_LEN) == "WEBP" -> webp.read(bytes)
            else -> null
        }
    }

    /**
     * The same, from base64 text. Only the HEADER WINDOW is read — a screenshot is megabytes and
     * every format here is settled inside the first few dozen bytes, so touching the whole payload
     * to reach two integers is exactly the waste this seam exists to avoid.
     *
     * [data] is a CharSequence and the scan STOPS at the window, both deliberately: the first
     * version filtered whitespace across the entire string before taking 128 chars, so the law was
     * a comment the code contradicted (codex-splice, by reading it — the existing arm could not
     * catch it, because answering correctly despite a long tail is true of both versions).
     * CharSequence is what lets a test count the characters actually touched, which turns the law
     * into an assertion.
     *
     * Invalid or truncated base64 returns null (unknown), never an exception and never a drop.
     */
    public fun probeBase64(data: CharSequence): ImagePx? {
        val window = StringBuilder(HEADER_B64_CHARS)
        for (c in data) {
            if (!c.isWhitespace()) window.append(c)
            if (window.length == HEADER_B64_CHARS) break
        }
        val aligned = window.substring(0, window.length - window.length % B64_GROUP)
        return runCatching { Base64.getDecoder().decode(aligned) }.getOrNull()?.let { probe(it) }
    }

    // The IHDR tag is REQUIRED, not just the 8-byte magic: the first chunk's length field alone
    // would happily read four arbitrary bytes as a width on a file that merely starts like a PNG.
    // And IHDR's own DECLARED length must be the spec's 13 (grok-splice, sweeping for siblings of
    // the JPEG and WebP borrows): a chunk announcing a different size is not an IHDR, and reading
    // width and height out of it anyway is the same defect wearing a third format.
    private fun png(b: ByteArray): ImagePx? {
        val tagged = b.size >= PNG_MIN && at.ascii(b, PNG_IHDR, TAG_LEN) == "IHDR"
        val header = tagged && at.beU32(b, PNG_CHUNK_LEN) == IHDR_DECLARED_LEN
        return if (header) at.px(at.beU32(b, PNG_W), at.beU32(b, PNG_H), "png") else null
    }

    private fun gif(b: ByteArray): ImagePx? =
        if (b.size >= GIF_MIN) {
            at.px(at.leU16(b, GIF_W).toLong(), at.leU16(b, GIF_H).toLong(), "gif")
        } else {
            null
        }

    // Walk the marker chain to a real frame header. Dimensions live on SOFn and nowhere else, so
    // APPn/DQT/DHT/COM are skipped by their own length, and the walk stops at SOS (entropy data
    // starts, and nothing past it is a marker) rather than guessing.
    private fun jpeg(b: ByteArray): ImagePx? {
        var i = magics.jpegSoi.size
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

    /** A frame header's own DECLARED length is load-bearing. Without it a SOF claiming length 2 —
     *  an empty segment — still had its height and width read out of whatever bytes happened to
     *  follow, and the probe answered a confident 1x1 for a header carrying no dimensions at all. */
    private fun sofDims(b: ByteArray, seg: Int): ImagePx? {
        val len = at.beU16(b, seg)
        val whole = len >= SOF_SEGMENT_MIN && seg + len <= b.size
        return if (whole) {
            at.px(at.beU16(b, seg + SOF_W).toLong(), at.beU16(b, seg + SOF_H).toLong(), "jpeg")
        } else {
            null
        }
    }
}

private const val TAG_LEN = 4

// How much base64 to read: 128 chars is 96 bytes, and the deepest header this file reads (a WebP
// VP8X canvas) is settled by byte 30.
private const val HEADER_B64_CHARS = 128
private const val B64_GROUP = 4

private const val PNG_CHUNK_LEN = 8
private const val PNG_IHDR = 12
private const val PNG_W = 16
private const val PNG_H = 20
private const val PNG_MIN = 24

// IHDR is fixed-size by the PNG spec: width(4) height(4) depth(1) colour(1) compression(1)
// filter(1) interlace(1).
private const val IHDR_DECLARED_LEN = 13L

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

// length(2) + precision(1) + height(2) + width(2) + component count(1). A SOF declaring less than
// this cannot be carrying dimensions, whatever bytes sit after it.
private const val SOF_SEGMENT_MIN = 8

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
