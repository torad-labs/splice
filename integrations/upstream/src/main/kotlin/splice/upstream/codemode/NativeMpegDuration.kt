package splice.upstream.codemode

// why: An MPEG audio frame begins with a four-byte header, also the width of Xing integer fields.
private const val MPEG_HEADER_BYTES: Int = 4

// why: The three high bits following the first 0xff octet complete the eleven-bit MPEG frame sync word.
private const val MPEG_SYNC: Int = 0xe0

// why: The first octet of an MPEG frame sync word has all eight bits set.
private const val MPEG_BYTE: Int = 0xff

// why: MPEG bitrate tables are expressed in decimal kilobits per second.
private const val MPEG_KILOBIT: Int = 1_000

// why: An MPEG-1 Layer II/III frame carries 1152 per-channel audio samples.
private const val MPEG_ONE_SAMPLES: Int = 1_152

// why: An MPEG-2/2.5 Layer III frame carries 576 per-channel audio samples.
private const val MPEG_TWO_SAMPLES: Int = 576

// why: An MPEG Layer I frame carries 384 per-channel audio samples.
private const val MPEG_LAYER_ONE_SAMPLES: Int = 384

// why: Layer II and MPEG-1 Layer III frame length uses 144 times bitrate divided by sample rate.
private const val MPEG_FRAME_FACTOR: Int = 144

// why: MPEG-2/2.5 Layer III frame length uses half the MPEG-1 coefficient because it has one granule.
private const val MPEG_TWO_FRAME_FACTOR: Int = 72

// why: Layer I frame length is twelve bitrate/rate slots, each slot occupying four bytes.
private const val MPEG_LAYER_ONE_FACTOR: Int = 12

// why: The ID3v2 tag header occupies ten bytes before its declared payload.
private const val MPEG_ID3_HEADER: Int = 10

// why: The ID3v2 payload size begins after its signature, version, revision and flags.
private const val MPEG_ID3_SIZE_OFFSET: Int = 6

// why: Each ID3v2 size octet contributes seven payload bits; its high bit remains clear.
private const val MPEG_SYNCSAFE_BITS: Int = 7

// why: Match Symphonia's estimator: sample until the frame count exceeds sixteen.
private const val MPEG_ESTIMATE_MAX_FRAMES: Int = 16

// why: Match Symphonia's estimator: stop sampling once accumulated frame lengths exceed 16 KiB.
private const val MPEG_ESTIMATE_MAX_BYTES: Int = 16 * 1_024

// why: The MPEG header encodes version 1 as the two-bit value 11.
private const val MPEG_VERSION_ONE: Int = 3

// why: The MPEG header encodes Layer I as the two-bit value 11.
private const val MPEG_LAYER_ONE: Int = 3

// why: The MPEG bitrate index occupies the upper four bits of the third header octet.
private const val MPEG_NIBBLE_BITS: Int = 4

// why: Bitrate indices 1 through 14 are defined; zero is free format and fifteen is invalid.
private const val MPEG_BITRATE_LAST: Int = 14

// why: MPEG-2 sample rates are half their MPEG-1 table entries.
private const val MPEG_SAMPLE_RATE_HALF: Int = 2

// why: MPEG-2.5 sample rates are one quarter of their MPEG-1 table entries.
private const val MPEG_SAMPLE_RATE_QUARTER: Int = 4

// why: Clear the high bit when decoding each seven-bit ID3v2 size group.
private const val MPEG_SYNCSAFE_MASK: Int = 0x7f

internal object NativeMpegDuration {
    private val rates = table("44100 48000 32000")
    private val oneBitrates = arrayOf(
        table("0 32 40 48 56 64 80 96 112 128 160 192 224 256 320"),
        table("0 32 48 56 64 80 96 112 128 160 192 224 256 320 384"),
        table("0 32 64 96 128 160 192 224 256 288 320 352 384 416 448"),
    )
    private val twoBitrates = arrayOf(
        table("0 8 16 24 32 40 48 56 64 80 96 112 128 144 160"),
        table("0 32 48 56 64 80 96 112 128 144 160 176 192 224 256"),
    )

    private fun table(value: String): IntArray = value.split(' ').map(String::toInt).toIntArray()

    fun read(data: NativeAudioBytes): Double? {
        val first = first(data) ?: return null
        val tag = NativeMpegTags.read(data, first.first, first.second)
        val samples = if (tag != null) tag.samples else estimate(data, first.first)?.times(first.second.samples)
        return samples?.toDouble()?.div(first.second.rate)
    }

    private fun first(data: NativeAudioBytes): Pair<Int, NativeMpegFrame>? {
        var offset = id3End(data)
        while (data.has(offset, MPEG_HEADER_BYTES)) {
            val current = frame(data, offset)
            if (current != null && data.has(offset, current.bytes)) {
                val nextOffset = offset + current.bytes
                val next = if (data.has(nextOffset, MPEG_HEADER_BYTES)) frame(data, nextOffset) else current
                if (next != null && next.similar(current)) return offset to current
            }
            offset++
        }
        return null
    }

    private fun estimate(data: NativeAudioBytes, start: Int): Long? {
        var offset = start
        var bytes = 0
        var frames = 0
        while (data.has(offset, MPEG_HEADER_BYTES)) {
            val current = frame(data, offset)?.takeIf { data.has(offset, it.bytes) } ?: return null
            bytes += current.bytes
            frames++
            offset += current.bytes
            if (frames > MPEG_ESTIMATE_MAX_FRAMES || bytes > MPEG_ESTIMATE_MAX_BYTES) {
                return ((data.size - start).toDouble() / (bytes.toDouble() / frames)).toLong()
            }
        }
        return null
    }

    private fun id3End(data: NativeAudioBytes): Int {
        if (!data.ascii(0, "ID3") || !data.has(0, MPEG_ID3_HEADER)) return 0
        var size = 0
        repeat(MPEG_HEADER_BYTES) { index ->
            size = size shl MPEG_SYNCSAFE_BITS or (data.byte(MPEG_ID3_SIZE_OFFSET + index) and MPEG_SYNCSAFE_MASK)
        }
        return minOf(data.size, size + MPEG_ID3_HEADER)
    }

    private fun frame(data: NativeAudioBytes, offset: Int): NativeMpegFrame? {
        if (data.byte(offset) != MPEG_BYTE || data.byte(offset + 1) and MPEG_SYNC != MPEG_SYNC) return null
        val version = data.byte(offset + 1) shr MPEG_VERSION_ONE and MPEG_VERSION_ONE
        val layer = data.byte(offset + 1) shr 1 and MPEG_VERSION_ONE
        val bitrateIndex = data.byte(offset + 2) shr MPEG_NIBBLE_BITS
        val rateIndex = data.byte(offset + 2) shr 2 and MPEG_VERSION_ONE
        if (!valid(version, layer, bitrateIndex, rateIndex)) return null
        val rate = sampleRate(version, rateIndex)
        val bitrate = bitrate(version, layer, bitrateIndex)
        val padding = data.byte(offset + 2) shr 1 and 1
        val size = size(layer, version, rate, bitrate, padding)
        val mono = data.byte(offset + MPEG_VERSION_ONE) shr MPEG_ID3_SIZE_OFFSET == MPEG_VERSION_ONE
        return NativeMpegFrame(
            size.first,
            size.second,
            rate,
            version,
            layer,
            if (mono) 1 else 2,
            data.byte(offset + 1) and 1 == 0,
        )
    }

    private fun valid(version: Int, layer: Int, bitrateIndex: Int, rateIndex: Int): Boolean {
        val encoding = version != 1 && layer != 0
        val rate = bitrateIndex in 1..MPEG_BITRATE_LAST && rateIndex != MPEG_VERSION_ONE
        return encoding && rate
    }

    private fun sampleRate(version: Int, index: Int): Int = rates[index] / when (version) {
        MPEG_VERSION_ONE -> 1
        2 -> MPEG_SAMPLE_RATE_HALF
        else -> MPEG_SAMPLE_RATE_QUARTER
    }

    private fun bitrate(version: Int, layer: Int, index: Int): Int =
        if (version == MPEG_VERSION_ONE) {
            oneBitrates[layer - 1][index]
        } else {
            twoBitrates[if (layer == MPEG_LAYER_ONE) 1 else 0][index]
        }

    private fun size(layer: Int, version: Int, rate: Int, bitrate: Int, padding: Int): Pair<Int, Int> {
        val bytes: Int
        val samples: Int
        if (layer == MPEG_LAYER_ONE) {
            bytes = (MPEG_LAYER_ONE_FACTOR * bitrate * MPEG_KILOBIT / rate + padding) * MPEG_HEADER_BYTES
            samples = MPEG_LAYER_ONE_SAMPLES
        } else {
            val short = layer == 1 && version != MPEG_VERSION_ONE
            val factor = if (short) MPEG_TWO_FRAME_FACTOR else MPEG_FRAME_FACTOR
            bytes = factor * bitrate * MPEG_KILOBIT / rate + padding
            samples = if (short) MPEG_TWO_SAMPLES else MPEG_ONE_SAMPLES
        }
        return bytes to samples
    }
}

internal data class NativeMpegFrame(
    val bytes: Int,
    val samples: Int,
    val rate: Int,
    val version: Int,
    val layer: Int,
    val channels: Int,
    val hasCrc: Boolean,
) {
    fun similar(other: NativeMpegFrame): Boolean {
        val encoding = version == other.version && layer == other.layer
        return encoding && rate == other.rate && channels == other.channels
    }
}
