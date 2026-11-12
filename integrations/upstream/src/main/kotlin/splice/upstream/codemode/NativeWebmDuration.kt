package splice.upstream.codemode

// why: Matroska assigns this element identifier to Segment.
private const val EBML_SEGMENT: Long = 0x18538067

// why: Matroska assigns this element identifier to Segment Info.
private const val EBML_INFO: Long = 0x1549a966

// why: Matroska assigns this element identifier to the floating-point Segment duration.
private const val EBML_DURATION: Long = 0x4489

// why: Matroska assigns this element identifier to TimestampScale.
private const val EBML_TIMECODE_SCALE: Long = 0x2ad7b1

// why: Matroska defaults TimestampScale to one million nanoseconds per duration tick.
private const val EBML_DEFAULT_SCALE: Long = 1_000_000

// why: Convert Matroska nanosecond-scaled duration to seconds for the native token estimate.
private const val EBML_NANOS_PER_SECOND: Double = 1_000_000_000.0

// why: An EBML variable-length size can occupy at most eight bytes.
private const val EBML_MAX_INTEGER_BYTES: Int = 8

// why: The first set high bit of an EBML integer selects its encoded byte width.
private const val EBML_HIGH_BIT: Int = 0x80

// why: Each subsequent EBML integer octet contributes eight bits.
private const val EBML_BYTE_BITS: Int = 8

internal object NativeWebmDuration {
    fun read(data: NativeAudioBytes): Double? {
        val root = Element(0, 0, data.size)
        val segment = children(data, root).firstOrNull { it.id == EBML_SEGMENT } ?: return null
        val info = children(data, segment).firstOrNull { it.id == EBML_INFO } ?: return null
        val values = children(data, info).toList()
        val scale = values.firstOrNull { it.id == EBML_TIMECODE_SCALE }?.let {
            data.big(it.payload, it.end - it.payload)
        } ?: EBML_DEFAULT_SCALE
        val duration = values.firstOrNull { it.id == EBML_DURATION }?.let {
            data.float(it.payload, it.end - it.payload)
        }
        return duration?.times(scale)?.div(EBML_NANOS_PER_SECOND)
    }

    private fun children(data: NativeAudioBytes, parent: Element): Sequence<Element> = sequence {
        var offset = parent.payload
        var reading = true
        while (offset < parent.end && reading) {
            val id = integer(data, offset, marker = true)
            val length = id?.let { integer(data, offset + it.second, marker = false) }
            if (id == null || length == null) {
                reading = false
            } else {
                val payload = offset + id.second + length.second
                val remaining = parent.end - payload
                val size = if (length.first < 0 || length.first > remaining) remaining else length.first.toInt()
                val end = payload + size
                yield(Element(id.first, payload, end))
                offset = end
            }
        }
    }

    private fun integer(data: NativeAudioBytes, offset: Int, marker: Boolean): Pair<Long, Int>? {
        if (!data.has(offset, 1)) return null
        var mask = EBML_HIGH_BIT
        var length = 1
        while (mask > 0 && data.byte(offset) and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (length > EBML_MAX_INTEGER_BYTES || !data.has(offset, length)) return null
        val value = data.big(offset, length)
        val raw = if (marker) value else value and (mask.toLong() shl ((length - 1) * EBML_BYTE_BITS)).inv()
        return raw to length
    }

    private data class Element(val id: Long, val payload: Int, val end: Int)
}
