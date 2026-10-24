package splice.upstream.codemode

// why: An Ogg page has a 27-byte fixed header before its segment table.
private const val OGG_HEADER_BYTES: Int = 27

// why: The last octet of the fixed Ogg page header gives the number of lacing segments.
private const val OGG_SEGMENT_COUNT: Int = 26

// why: The Ogg logical-stream serial number begins at byte fourteen of a page header.
private const val OGG_SERIAL_OFFSET: Int = 14

// why: The Ogg granule position begins at byte six of a page header.
private const val OGG_GRANULE_OFFSET: Int = 6

// why: An Ogg stream serial number and Vorbis sample rate occupy four bytes.
private const val OGG_WORD_BYTES: Int = 4

// why: An Ogg granule position occupies eight bytes.
private const val OGG_LONG_BYTES: Int = 8

// why: The Vorbis identification packet puts sample rate after signature, version and channel count.
private const val OGG_VORBIS_RATE: Int = 12

// why: The fixed OpusHead identification header occupies nineteen bytes.
private const val OGG_OPUS_HEADER_BYTES: Int = 19

// why: Opus Ogg granule positions always use a 48 kHz timebase, independently of input sample rate.
private const val OGG_OPUS_RATE: Int = 48_000

internal object NativeOggDuration {
    fun read(data: NativeAudioBytes): Double? {
        var offset = 0
        var track: Track? = null
        var granule: Long? = null
        while (data.has(offset, OGG_HEADER_BYTES)) {
            val page = page(data, offset) ?: break
            if (track == null) track = track(data, page.payload, page.serial)
            if (page.serial == track?.serial) granule = page.granule
            offset = page.end
        }
        val selected = track ?: return null
        // Symphonia's declared track duration retains codec delay; token estimates do not trim Opus preskip.
        return granule?.takeIf { it >= 0 }?.toDouble()?.div(selected.rate)
    }

    private fun page(data: NativeAudioBytes, offset: Int): Page? {
        if (!data.ascii(offset, "OggS")) return null
        val segments = data.byte(offset + OGG_SEGMENT_COUNT)
        val payload = offset + OGG_HEADER_BYTES + segments
        if (!data.has(offset, OGG_HEADER_BYTES + segments)) return null
        val size = (0 until segments).sumOf { data.byte(offset + OGG_HEADER_BYTES + it) }
        return if (data.has(payload, size)) {
            Page(
                payload,
                payload + size,
                data.little(offset + OGG_SERIAL_OFFSET, OGG_WORD_BYTES),
                data.little(offset + OGG_GRANULE_OFFSET, OGG_LONG_BYTES),
            )
        } else {
            null
        }
    }

    private fun track(data: NativeAudioBytes, payload: Int, serial: Long): Track? = when {
        data.ascii(payload + 1, "vorbis") && data.has(payload, OGG_VORBIS_RATE + OGG_WORD_BYTES) -> {
            data.little(payload + OGG_VORBIS_RATE, OGG_WORD_BYTES).takeIf { it > 0 }?.let { Track(serial, it) }
        }
        data.ascii(payload, "OpusHead") && data.has(payload, OGG_OPUS_HEADER_BYTES) ->
            Track(serial, OGG_OPUS_RATE.toLong())
        else -> null
    }

    private data class Track(val serial: Long, val rate: Long)
    private data class Page(val payload: Int, val end: Int, val serial: Long, val granule: Long)
}
