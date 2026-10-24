package splice.upstream.codemode

private const val OGG_HEADER_BYTES: Int = 27
private const val OGG_SEGMENT_COUNT: Int = 26
private const val OGG_SERIAL_OFFSET: Int = 14
private const val OGG_GRANULE_OFFSET: Int = 6
private const val OGG_WORD_BYTES: Int = 4
private const val OGG_LONG_BYTES: Int = 8
private const val OGG_VORBIS_RATE: Int = 12
private const val OGG_OPUS_SKIP: Int = 10
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
        return granule?.takeIf { it >= selected.skip }?.minus(selected.skip)?.toDouble()?.div(selected.rate)
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
            data.little(payload + OGG_VORBIS_RATE, OGG_WORD_BYTES).takeIf { it > 0 }?.let { Track(serial, it, 0) }
        }
        data.ascii(payload, "OpusHead") && data.has(payload, OGG_OPUS_SKIP + 2) ->
            Track(serial, OGG_OPUS_RATE.toLong(), data.little(payload + OGG_OPUS_SKIP, 2))
        else -> null
    }

    private data class Track(val serial: Long, val rate: Long, val skip: Long)
    private data class Page(val payload: Int, val end: Int, val serial: Long, val granule: Long)
}
