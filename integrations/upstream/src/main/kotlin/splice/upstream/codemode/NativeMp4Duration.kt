package splice.upstream.codemode

// why: An ISO BMFF atom starts with a four-byte length and a four-byte type.
private const val ISO_HEADER_BYTES: Int = 8

// why: ISO BMFF type identifiers and ordinary unsigned integer fields occupy four bytes.
private const val ISO_WORD_BYTES: Int = 4

// why: Extended atom sizes and version-one media durations use eight-byte integers.
private const val ISO_LONG_BYTES: Int = 8

// why: In a version-one mdhd payload, version/flags and two 64-bit timestamps precede the timescale.
private const val ISO_VERSION_ONE_TIMESCALE: Int = 20

// why: In a version-zero mdhd payload, version/flags and two 32-bit timestamps precede the timescale.
private const val ISO_VERSION_ZERO_TIMESCALE: Int = 12

// why: An hdlr payload places handler_type after version/flags and the pre_defined word.
private const val ISO_HANDLER_TYPE: Int = 8

internal object NativeMp4Duration {
    fun read(data: NativeAudioBytes): Double? {
        val root = NativeIsoBox(0, data.size)
        val movie = NativeIsoBoxes.children(data, root).firstOrNull { data.ascii(it.start + ISO_WORD_BYTES, "moov") }
            ?: return null
        return NativeIsoBoxes.children(data, movie).filter { data.ascii(it.start + ISO_WORD_BYTES, "trak") }
            .mapNotNull { track -> duration(data, track) }.firstOrNull()
    }

    private fun duration(data: NativeAudioBytes, track: NativeIsoBox): Double? {
        val media = child(data, track, "mdia")
        val handler = media?.let { child(data, it, "hdlr") }
        val header = media?.let { child(data, it, "mdhd") }
        if (handler == null || header == null) return null
        return if (data.ascii(handler.payload + ISO_HANDLER_TYPE, "soun") && data.has(header.payload, 1)) {
            durationHeader(data, header)
        } else {
            null
        }
    }

    private fun durationHeader(data: NativeAudioBytes, header: NativeIsoBox): Double? {
        val version = data.byte(header.payload)
        val scaleOffset = header.payload + if (version == 1) ISO_VERSION_ONE_TIMESCALE else ISO_VERSION_ZERO_TIMESCALE
        val durationBytes = if (version == 1) ISO_LONG_BYTES else ISO_WORD_BYTES
        if (!data.has(scaleOffset, ISO_WORD_BYTES + durationBytes)) return null
        val scale = data.big(scaleOffset, ISO_WORD_BYTES)
        val duration = data.big(scaleOffset + ISO_WORD_BYTES, durationBytes)
        return duration.toDouble().div(scale).takeIf { scale > 0 && duration >= 0 }
    }

    private fun child(data: NativeAudioBytes, parent: NativeIsoBox, type: String): NativeIsoBox? =
        NativeIsoBoxes.children(data, parent).firstOrNull { data.ascii(it.start + ISO_WORD_BYTES, type) }
}

internal data class NativeIsoBox(val payload: Int, val end: Int, val start: Int = 0)

internal object NativeIsoBoxes {
    fun children(data: NativeAudioBytes, parent: NativeIsoBox): Sequence<NativeIsoBox> = sequence {
        var offset = parent.payload
        while (offset < parent.end) {
            val box = next(data, offset, parent.end) ?: break
            yield(box)
            offset = box.end
        }
    }

    private fun next(data: NativeAudioBytes, offset: Int, end: Int): NativeIsoBox? {
        if (!data.has(offset, ISO_HEADER_BYTES)) return null
        val shortSize = data.big(offset, ISO_WORD_BYTES)
        val header = if (shortSize == 1L) ISO_HEADER_BYTES + ISO_LONG_BYTES else ISO_HEADER_BYTES
        if (!data.has(offset, header)) return null
        val size = when (shortSize) {
            0L -> (end - offset).toLong()
            1L -> data.big(offset + ISO_HEADER_BYTES, ISO_LONG_BYTES)
            else -> shortSize
        }
        return if (size < header || size > end - offset) {
            null
        } else {
            NativeIsoBox(
                offset + header,
                offset + size.toInt(),
                offset,
            )
        }
    }
}
