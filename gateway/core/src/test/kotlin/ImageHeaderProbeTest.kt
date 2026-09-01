// NEW (DR-155): the header probe and the vendor floor built on it. Every arm here is written
// against SYNTHETIC REAL HEADERS, not a stand-in — the pre-existing "aGk=" fixtures scattered
// through the dialect suites are base64 of the word "hi" and forward purely because they are
// nonempty, so they could never have caught this and cannot pin it now.
//
// The defect being pinned is live and measured: six claude-grok turns in the DR-152 soak died on a
// byte-identical HTTP 400, code=invalid_image, "Image dimensions 1x1 are too small. Both width and
// height must be at least 8 pixels." A 1x1 PNG is the first arm below.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.media.ImageFloor
import splice.core.media.ImageHeaderProbe
import splice.core.media.ImagePx
import splice.core.wire.MediaSource
import java.util.Base64

class ImageHeaderProbeTest {

    private val probe = ImageHeaderProbe()

    @Test
    fun `a png header yields its IHDR dimensions - DR-155`() {
        assertEquals(ImagePx(1, 1, "png"), probe.probe(ImageBytesFixture().png(1, 1)))
        assertEquals(ImagePx(1920, 1080, "png"), probe.probe(ImageBytesFixture().png(1920, 1080)))
    }

    // The IHDR tag is load-bearing, not decoration. Without it the first chunk's LENGTH field sits
    // exactly where a width would be read from, so a non-PNG that merely opens with the magic would
    // report a confident wrong size. Mutant: drop the ascii(12,4)=="IHDR" clause.
    @Test
    fun `a png magic without an IHDR chunk is unknown, not a guessed width - DR-155`() {
        val fake = ImageBytesFixture().png(4096, 4096).copyOf()
        "IDAT".toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> fake[PNG_IHDR_AT + i] = b }
        assertNull(probe.probe(fake))
    }

    @Test
    fun `both gif signatures read their little-endian screen descriptor - DR-155`() {
        assertEquals(ImagePx(1, 1, "gif"), probe.probe(ImageBytesFixture().gif("GIF87a", 1, 1)))
        assertEquals(ImagePx(640, 480, "gif"), probe.probe(ImageBytesFixture().gif("GIF89a", 640, 480)))
    }

    // HEIGHT precedes WIDTH in a JPEG frame header, which is the opposite of every other format
    // here. A non-square fixture is the only thing that can catch a transposed pair; 8x9 would also
    // pass a floor check either way round, which is exactly why the probe is pinned separately.
    @Test
    fun `a baseline jpeg frame header is found past an APP0 segment, height first - DR-155`() {
        val fx = ImageBytesFixture()
        val jpeg = fx.jpeg(SOF0, w = 200, h = 100, lead = fx.segment(APP0, ByteArray(APP0_PAYLOAD)))
        assertEquals(ImagePx(200, 100, "jpeg"), probe.probe(jpeg))
    }

    // Progressive JPEG is SOF2, and it is most of what a browser produces. Mutant: match only 0xC0.
    @Test
    fun `a progressive jpeg is a frame header like any other - DR-155`() {
        assertEquals(ImagePx(64, 32, "jpeg"), probe.probe(ImageBytesFixture().jpeg(SOF2, w = 64, h = 32)))
    }

    // 0xC4 (Huffman tables) shares the 0xC0..0xCF range but carries no dimensions. Reading it as a
    // frame yields whatever the table's first bytes happen to be. Mutant: drop NOT_A_FRAME.
    @Test
    fun `a huffman table inside the SOF range is skipped, not read as a frame - DR-155`() {
        val fx = ImageBytesFixture()
        val dht = fx.segment(DHT, ByteArray(DHT_PAYLOAD) { HUFFMAN_FILL })
        assertEquals(ImagePx(16, 16, "jpeg"), probe.probe(fx.jpeg(SOF0, w = 16, h = 16, lead = dht)))
    }

    // Past SOS the bytes are entropy-coded scan data, and a real scan is FULL of 0xFF bytes that are
    // compressed pixels rather than markers. So the fixture hides a byte-perfect fake frame header
    // just inside the scan: a walk that does not stop reads it and reports 4096x4096 with complete
    // confidence. Mutant: drop the STOP_MARKERS clause.
    //
    // The first version of this arm was DECORATIVE and the mutation run caught it — its scan was
    // filler bytes, so the walk ran off the end and answered null either way, which is the same
    // answer for the opposite reason. An arm that cannot fail is not evidence.
    @Test
    fun `the jpeg walk stops at the start of scan rather than guessing - DR-155`() {
        val fx = ImageBytesFixture()
        val scan = fx.segment(SOS, ByteArray(SOS_PAYLOAD)) + fx.sofSegment(SOF0, w = BOGUS, h = BOGUS)
        assertNull(probe.probe(fx.bytes(0xFF, 0xD8) + scan))
    }

    // VP8X stores canvas size MINUS ONE, so the live 1x1 defect is encoded as all zeroes here.
    // Mutant: drop the +1 and every WebP becomes one pixel smaller than it is — including turning
    // a legal 8x8 into a below-floor 7x7 drop.
    @Test
    fun `a webp extended header decodes its minus-one canvas - DR-155`() {
        val fx = ImageBytesFixture()
        assertEquals(ImagePx(1, 1, "webp"), probe.probe(fx.riff(fx.vp8x(1, 1))))
        assertEquals(ImagePx(8, 8, "webp"), probe.probe(fx.riff(fx.vp8x(8, 8))))
    }

    @Test
    fun `a lossless webp decodes its packed 14-bit dimensions - DR-155`() {
        val fx = ImageBytesFixture()
        assertEquals(ImagePx(1, 1, "webp"), probe.probe(fx.riff(fx.vp8l(1, 1))))
        assertEquals(ImagePx(300, 200, "webp"), probe.probe(fx.riff(fx.vp8l(300, 200))))
    }

    // The keyframe start code is checked because without it an interframe's tag bytes read as a
    // plausible size. Mutant: drop the startsWith(VP8_START) clause and the corrupt fixture below
    // starts reporting dimensions.
    @Test
    fun `a lossy webp needs its keyframe start code to be read - DR-155`() {
        val fx = ImageBytesFixture()
        assertEquals(ImagePx(120, 90, "webp"), probe.probe(fx.riff(fx.vp8(120, 90))))
        val corrupt = fx.riff(fx.chunk("VP8 ", fx.bytes(0, 0, 0, 0x9D, 0x01, 0x2B) + fx.le16(120) + fx.le16(90)))
        assertNull(probe.probe(corrupt))
    }

    // A colour profile ahead of the dimension chunk is ordinary encoder output. Mutant: assume the
    // dimension chunk is first.
    @Test
    fun `a webp dimension chunk is found behind an ICC profile chunk - DR-155`() {
        val fx = ImageBytesFixture()
        val withProfile = fx.riff(fx.chunk("ICCP", ByteArray(ICCP_PAYLOAD)) + fx.vp8x(64, 64))
        assertEquals(ImagePx(64, 64, "webp"), probe.probe(withProfile))
    }

    @Test
    fun `a truncated or unrecognised header is unknown, never a dimension - DR-155`() {
        val fx = ImageBytesFixture()
        assertNull(probe.probe(fx.png(8, 8).copyOf(PNG_TRUNCATED)))
        assertNull(probe.probe(fx.riff(fx.vp8x(8, 8)).copyOf(WEBP_TRUNCATED)))
        assertNull(probe.probe("not an image at all".toByteArray(Charsets.US_ASCII)))
        assertNull(probe.probe(ByteArray(0)))
    }

    @Test
    fun `base64 is decoded only far enough to answer, and bad base64 is unknown - DR-155`() {
        val fx = ImageBytesFixture()
        val big = Base64.getEncoder().encodeToString(fx.png(1, 1) + ByteArray(BIG_TAIL) { PIXEL_FILL })
        assertEquals(ImagePx(1, 1, "png"), probe.probeBase64(big))
        assertNull(probe.probeBase64("!!!! not base64 !!!!"))
        assertNull(probe.probeBase64(""))
    }

    // The header-window law, made OBSERVABLE. It was previously only a comment, and the comment was
    // false: probeBase64 filtered whitespace across the ENTIRE payload before taking 128 chars, so
    // a multi-megabyte screenshot was copied in full to reach two integers — twice for an undersized
    // one, since the marker re-probes. The arm above could not catch it, because "answer correctly
    // despite a long tail" is true of both versions. Counting the characters actually touched is
    // what turns the law into an assertion. Found by codex-splice reading the code, not the tests.
    @Test
    fun `base64 is never scanned past the header window - DR-155`() {
        val fx = ImageBytesFixture()
        // The tail is encoded WITH the header, not concatenated after it: appending characters to a
        // finished base64 string puts its "=" padding in the middle, and the window then fails to
        // decode for a reason that has nothing to do with what this arm measures.
        val encoded = Base64.getEncoder().encodeToString(fx.png(1, 1) + ByteArray(HUGE_TAIL) { PIXEL_FILL })
        val payload = CountingChars(encoded)
        assertEquals(ImagePx(1, 1, "png"), probe.probeBase64(payload))
        assertTrue(
            payload.reads <= WINDOW_READ_CAP,
            "touched ${payload.reads} chars of a ${payload.length}-char payload to read two integers",
        )
    }
}

// A HOSTILE header is an input, not a fact. Every arm here plants a number a real encoder would
// never write and asserts the probe answers UNKNOWN — never a dimension it invented, and never an
// exception. All five holes were found by codex-splice's adversarial replay and independently
// reproduced by grok-splice; each shipped in the first version of this file.
class MalformedHeaderTest {

    private val probe = ImageHeaderProbe()
    private val fx = ImageBytesFixture()

    // The worst of the five, because it made splice DELETE a picture. A 32-bit width read into an
    // Int made 0x80000000 negative, and ImageFloor's "width < minimum" test then reported a huge
    // image as undersized. Fail-CLOSED on a malformed header is the exact direction this policy
    // must never take. Mutant: read the u32 into an Int.
    @Test
    fun `a png width too large for an Int is unknown, never an undersized image - DR-155`() {
        val huge = fx.png32(OVERFLOW_U32, OVERFLOW_U32)
        assertNull(probe.probe(huge), "an unrepresentable dimension is unknown")
        assertNull(ImageFloor(XAI_FLOOR).violatedMinimum(base64(huge)), "and must NOT be dropped")
    }

    // The other end of the same range: zero is not a small image, it is a broken header.
    @Test
    fun `a zero dimension is unknown, not something small - DR-155`() {
        assertNull(probe.probe(fx.png(0, 0)))
        assertNull(ImageFloor(XAI_FLOOR).violatedMinimum(base64(fx.png(0, 0))))
    }

    // IHDR is fixed-size by the spec. A chunk announcing a different length is not an IHDR, and
    // reading width and height out of it anyway is the JPEG/WebP borrow wearing a third format.
    @Test
    fun `a png IHDR that misdeclares its own length is unknown - DR-155`() {
        val bad = fx.png(64, 64).copyOf()
        fx.be32(1).copyInto(bad, PNG_CHUNK_LEN_AT)
        assertNull(probe.probe(bad))
    }

    // A SOF declaring length 2 is an EMPTY segment. Reading height and width out of the bytes that
    // happen to follow returned a confident 1x1 — an invented dimension that then dropped a legal
    // image. Mutant: bound sofDims by the buffer only, not by the segment's declared length.
    @Test
    fun `a jpeg frame header shorter than its own fields is unknown - DR-155`() {
        val emptySof = fx.bytes(0xFF, 0xD8, 0xFF, SOF0, 0x00, 0x02) + fx.bytes(0x08) +
            fx.be16(1) + fx.be16(1) + fx.bytes(3, 1, 0x22, 0)
        assertNull(probe.probe(emptySof), "a SOF carrying no dimensions must not produce one")
    }

    // Same defect in RIFF: a chunk may only be read for the bytes it DECLARES it owns. A VP8X
    // claiming size 1 had ten bytes read past its own end and answered 8x8 — this direction
    // FORWARDS an undersized image, so the borrow lies in both directions.
    @Test
    fun `a webp chunk that under-declares its size is unknown - DR-155`() {
        val underDeclared = fx.riff(fx.chunkSized("VP8X", fx.vp8xBody(8, 8), declared = 1))
        assertNull(probe.probe(underDeclared))
    }

    // The one that could take a turn down rather than merely lie about it. `i += 8 + size + pad`
    // with an attacker-chosen unsigned size overflowed the cursor NEGATIVE, and the next iteration
    // indexed the array at a negative offset: ArrayIndexOutOfBoundsException out of a parser whose
    // stated law is that failure is always null. Mutant: make the cursor an Int again.
    @Test
    fun `a webp chunk size that would overflow the walk is unknown, not a crash - DR-155`() {
        val overflowing = fx.riff(fx.chunkSized("ICCP", ByteArray(ICCP_PAYLOAD), declared = Int.MAX_VALUE.toLong()))
        assertNull(probe.probe(overflowing))
        assertNull(ImageFloor(XAI_FLOOR).violatedMinimum(base64(overflowing)))
    }

    private fun base64(bytes: ByteArray): MediaSource =
        MediaSource(type = "base64", mediaType = "image/png", data = Base64.getEncoder().encodeToString(bytes))
}

/** A CharSequence that counts the characters actually read, so the header-window law can be
 *  asserted instead of described. */
class CountingChars(private val s: String) : CharSequence {
    var reads: Int = 0
        private set

    override val length: Int get() = s.length

    override fun get(index: Int): Char {
        reads++
        return s[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.subSequence(startIndex, endIndex)
}

class ImageFloorTest {

    private val fx = ImageBytesFixture()

    private fun source(bytes: ByteArray, mediaType: String = "image/png"): MediaSource =
        MediaSource(type = "base64", mediaType = mediaType, data = Base64.getEncoder().encodeToString(bytes))

    // THE DEFECT. With no floor configured this exact source rode upstream and xAI 400d the turn.
    @Test
    fun `the live 1x1 that killed six turns is proven under an 8px floor - DR-155`() {
        assertEquals(XAI_FLOOR, ImageFloor(XAI_FLOOR).violatedMinimum(source(fx.png(1, 1))))
    }

    // The default is the whole safety argument: a null floor must never decode and never drop, so
    // every head that did not opt in is byte-identical to before this existed.
    @Test
    fun `a null floor never drops, not even a real 1x1 - DR-155`() {
        assertNull(ImageFloor(null).violatedMinimum(source(fx.png(1, 1))))
        assertNull(ImageFloor(null).violatedMinimum(source(fx.riff(fx.vp8x(1, 1)), "image/webp")))
    }

    // Off-by-one in both directions and on both axes. Mutant: <= for <, or checking one edge.
    @Test
    fun `the floor is per-edge and the boundary value itself passes - DR-155`() {
        val floor = ImageFloor(XAI_FLOOR)
        assertEquals(XAI_FLOOR, floor.violatedMinimum(source(fx.png(7, 8))), "a short width violates")
        assertEquals(XAI_FLOOR, floor.violatedMinimum(source(fx.png(8, 7))), "a short height violates")
        assertNull(floor.violatedMinimum(source(fx.png(8, 8))), "the minimum itself is legal")
        assertNull(floor.violatedMinimum(source(fx.png(8, 9))), "and anything above it")
    }

    // (A zero or unrepresentable dimension used to be graded as "under the floor" here. It is a
    // BROKEN HEADER, not a small image, and it now answers unknown — see MalformedHeaderTest.)

    // Everything the proxy cannot READ forwards. This is the fail-open half of the policy and the
    // reason the return type is "the floor it is PROVEN under" rather than a boolean verdict:
    // dropping on uncertainty would silently delete images the vendor would have accepted.
    @Test
    fun `every unknown forwards rather than being dropped - DR-155`() {
        val floor = ImageFloor(XAI_FLOOR)
        assertNull(floor.violatedMinimum(null), "no source at all")
        assertNull(floor.violatedMinimum(MediaSource(type = "url", url = "https://example.test/a.png")), "url")
        assertNull(floor.violatedMinimum(MediaSource(type = "base64", data = "")), "empty data")
        assertNull(floor.violatedMinimum(MediaSource(type = "base64", data = "!!!!")), "undecodable base64")
        assertNull(floor.violatedMinimum(source("hi".toByteArray(Charsets.US_ASCII))), "the aGk= fixture")
        assertNull(floor.violatedMinimum(source(fx.png(1, 1).copyOf(PNG_TRUNCATED))), "a truncated png")
    }

    // The client's declared media_type is a claim, not a fact, and it is not what gets sent to the
    // vendor's decoder. Mutant: branch on mediaType instead of on the magic.
    @Test
    fun `classification comes from the bytes, not the declared media type - DR-155`() {
        val jpegBytesLabelledPng = source(fx.jpeg(SOF0, w = 1, h = 1), mediaType = "image/png")
        assertEquals(XAI_FLOOR, ImageFloor(XAI_FLOOR).violatedMinimum(jpegBytesLabelledPng))
    }

    // The marker text must name the real constraint. An operator reading "omitted" with no number
    // cannot tell a policy drop from a bug.
    @Test
    fun `the reason names the actual floor - DR-155`() {
        assertEquals("image edge below this backend's 8px minimum", ImageFloor(XAI_FLOOR).reason(XAI_FLOOR))
    }
}

/** Synthetic headers in the real wire layouts. A class rather than top-level helpers so the fixture
 *  travels with whichever test needs it and detekt's per-file rules stay satisfied. */
class ImageBytesFixture {

    fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    fun be16(v: Int): ByteArray = bytes((v ushr BITS_8) and BYTE, v and BYTE)

    fun be32(v: Int): ByteArray =
        bytes((v ushr BITS_24) and BYTE, (v ushr BITS_16) and BYTE, (v ushr BITS_8) and BYTE, v and BYTE)

    fun le16(v: Int): ByteArray = bytes(v and BYTE, (v ushr BITS_8) and BYTE)

    fun le24(v: Int): ByteArray = bytes(v and BYTE, (v ushr BITS_8) and BYTE, (v ushr BITS_16) and BYTE)

    fun le32(v: Int): ByteArray = le24(v) + bytes((v ushr BITS_24) and BYTE)

    // Long-valued writers, so an arm can plant a 32-bit number no Int can hold — which is exactly
    // the shape that made the probe report a huge image as undersized.
    fun be32L(v: Long): ByteArray = bytes(byteOf(v, BITS_24), byteOf(v, BITS_16), byteOf(v, BITS_8), byteOf(v, 0))

    fun le32L(v: Long): ByteArray = bytes(byteOf(v, 0), byteOf(v, BITS_8), byteOf(v, BITS_16), byteOf(v, BITS_24))

    private fun byteOf(v: Long, shift: Int): Int = ((v ushr shift) and BYTE.toLong()).toInt()

    fun png(w: Int, h: Int): ByteArray = png32(w.toLong(), h.toLong())

    fun png32(w: Long, h: Long): ByteArray =
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            be32(PNG_IHDR_LEN) + ascii("IHDR") + be32L(w) + be32L(h) + bytes(8, 6, 0, 0, 0)

    fun gif(signature: String, w: Int, h: Int): ByteArray =
        ascii(signature) + le16(w) + le16(h) + bytes(0xF7, 0x00, 0x00)

    fun segment(marker: Int, payload: ByteArray): ByteArray =
        bytes(0xFF, marker) + be16(payload.size + SEGMENT_LEN_SELF) + payload

    /** A frame header on its own, so an arm can plant one somewhere it does not belong. */
    fun sofSegment(sof: Int, w: Int, h: Int): ByteArray =
        segment(sof, bytes(8) + be16(h) + be16(w) + bytes(3, 1, 0x22, 0))

    fun jpeg(sof: Int, w: Int, h: Int, lead: ByteArray = ByteArray(0)): ByteArray =
        bytes(0xFF, 0xD8) + lead + sofSegment(sof, w, h)

    fun riff(chunks: ByteArray): ByteArray =
        ascii("RIFF") + le32(chunks.size + RIFF_FORM_LEN) + ascii("WEBP") + chunks

    fun chunk(tag: String, body: ByteArray): ByteArray = chunkSized(tag, body, body.size.toLong())

    /** A chunk whose DECLARED size can disagree with the bytes that follow it — the whole point of
     *  the under-declare and overflow arms. */
    fun chunkSized(tag: String, body: ByteArray, declared: Long): ByteArray {
        val pad = if (body.size % 2 == 1) bytes(0) else ByteArray(0)
        return ascii(tag) + le32L(declared) + body + pad
    }

    fun vp8xBody(w: Int, h: Int): ByteArray = bytes(0x10, 0, 0, 0) + le24(w - 1) + le24(h - 1)

    fun vp8x(w: Int, h: Int): ByteArray = chunk("VP8X", vp8xBody(w, h))

    fun vp8l(w: Int, h: Int): ByteArray = chunk("VP8L", bytes(0x2F) + le32(((h - 1) shl BITS_14) or (w - 1)))

    fun vp8(w: Int, h: Int): ByteArray =
        chunk("VP8 ", bytes(0, 0, 0, 0x9D, 0x01, 0x2A) + le16(w) + le16(h))

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
}

private const val BYTE = 0xFF
private const val BITS_8 = 8
private const val BITS_14 = 14
private const val BITS_16 = 16
private const val BITS_24 = 24

private const val XAI_FLOOR = 8
private const val PNG_IHDR_AT = 12
private const val PNG_IHDR_LEN = 13
private const val PNG_TRUNCATED = 20
private const val WEBP_TRUNCATED = 24
private const val SEGMENT_LEN_SELF = 2
private const val RIFF_FORM_LEN = 4

private const val SOF0 = 0xC0
private const val SOF2 = 0xC2
private const val DHT = 0xC4
private const val SOS = 0xDA
private const val APP0 = 0xE0
private const val APP0_PAYLOAD = 14
private const val DHT_PAYLOAD = 20
private const val HUFFMAN_FILL: Byte = 0x11
private const val SOS_PAYLOAD = 10
private const val BOGUS = 4096
private const val ICCP_PAYLOAD = 6
private const val BIG_TAIL = 4096
private const val PIXEL_FILL: Byte = 0x5A

private const val OVERFLOW_U32 = 0x80000000L
private const val PNG_CHUNK_LEN_AT = 8
private const val HUGE_TAIL = 750_000 // bytes; about a million base64 characters

// 128 chars of window plus slack. A payload a million characters long must cost about the window,
// not about the payload.
private const val WINDOW_READ_CAP = 200
