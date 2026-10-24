package splice.upstream.codemode

private const val WAVE_HEADER_BYTES: Int = 12
private const val WAVE_CHUNK_HEADER_BYTES: Int = 8
private const val WAVE_WORD_BYTES: Int = 4
private const val WAVE_FORMAT_RATE_OFFSET: Int = 8
private const val WAVE_MIN_FORMAT_BYTES: Int = 16

internal object NativeWaveDuration {
    fun read(data: NativeAudioBytes): Double? {
        if (!data.ascii(0, "RIFF") || !data.ascii(WAVE_CHUNK_HEADER_BYTES, "WAVE")) return null
        var offset = WAVE_HEADER_BYTES
        val metadata = Metadata()
        while (data.has(offset, WAVE_CHUNK_HEADER_BYTES)) {
            val size = data.little(offset + WAVE_WORD_BYTES, WAVE_WORD_BYTES)
            val payload = offset + WAVE_CHUNK_HEADER_BYTES
            metadata.accept(data, offset, payload, size)
            val next = payload.toLong() + size + size % 2
            if (next > data.size) break
            offset = next.toInt()
        }
        return metadata.duration()
    }

    private class Metadata {
        private var bytesPerSecond: Long? = null
        private var samplesBytes: Long? = null

        fun accept(data: NativeAudioBytes, offset: Int, payload: Int, size: Long) {
            if (data.ascii(offset, "fmt ") && data.has(payload, WAVE_MIN_FORMAT_BYTES)) {
                bytesPerSecond = data.little(payload + WAVE_FORMAT_RATE_OFFSET, WAVE_WORD_BYTES)
            }
            if (data.ascii(offset, "data")) samplesBytes = size
        }

        fun duration(): Double? = bytesPerSecond?.takeIf { it > 0 }?.let { rate ->
            samplesBytes?.toDouble()?.div(rate)
        }
    }
}
