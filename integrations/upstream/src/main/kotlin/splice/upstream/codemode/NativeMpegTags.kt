package splice.upstream.codemode

// why: The MPEG version field value 11 selects version-one side-information lengths.
private const val MPEG_V1: Int = 3

// why: MPEG frame headers and the integer fields in Xing/VBRI metadata occupy four bytes.
private const val MPEG_HEADER: Int = 4

// why: MPEG-1 stereo Layer III side information occupies 32 bytes.
private const val MPEG_SIDE_STEREO_ONE: Int = 32

// why: MPEG-1 mono and MPEG-2 stereo Layer III side information occupy 17 bytes.
private const val MPEG_SIDE_MONO_ONE: Int = 17

// why: MPEG-2/2.5 mono Layer III side information occupies nine bytes.
private const val MPEG_SIDE_MONO_TWO: Int = 9

// why: Xing/Info starts with its four-byte identifier followed by a four-byte flags word.
private const val MPEG_XING_HEADER: Int = 8

// why: Bit two of Xing flags declares the optional 100-byte table of contents.
private const val MPEG_XING_TOC_FLAG: Long = 4

// why: Bit three of Xing flags declares the optional quality word.
private const val MPEG_XING_QUALITY_FLAG: Long = 8

// why: The first 24 bytes of a LAME extension include encoder delay and padding.
private const val MPEG_LAME_MIN: Int = 24

// why: The complete LAME extension occupies 36 bytes, including its final CRC.
private const val MPEG_LAME_FULL: Int = 36

// why: LAME delay/padding starts after encoder, revision, filter, replay-gain, flags and bitrate fields.
private const val MPEG_LAME_TRIM_OFFSET: Int = 21

// why: LAME packs two twelve-bit trim quantities into three bytes.
private const val MPEG_LAME_TRIM_BYTES: Int = 3

// why: The upper twelve bits of LAME trim metadata encode the encoder delay.
private const val MPEG_LAME_TRIM_BITS: Int = 12

// why: The lower twelve bits of LAME trim metadata encode encoder padding.
private const val MPEG_LAME_TRIM_MASK: Int = 0xfff

// why: Match Symphonia's 528-sample decoder delay plus its one-sample alignment correction.
private const val MPEG_LAME_DELAY: Int = 528 + 1

// why: VBRI begins 32 bytes after the four-byte MPEG header.
private const val MPEG_VBRI_OFFSET: Int = 36

// why: Symphonia requires at least 26 bytes from the VBRI signature before accepting the tag.
private const val MPEG_VBRI_MIN: Int = 26

// why: The VBRI frame count follows its signature, version, delay, quality and encoded-byte count.
private const val MPEG_VBRI_FRAMES: Int = 14

// why: LAME tag validation uses the reflected CRC-16/ANSI polynomial, matching Symphonia Crc16AnsiLe.
private const val MPEG_CRC_POLYNOMIAL: Int = 0xa001

// why: CRC-16 processing consumes the eight bits of each metadata octet.
private const val MPEG_BYTE_BITS: Int = 8

internal data class NativeMpegTag(val samples: Long?)

/** Symphonia's Xing/Info, LAME trim/CRC and VBRI duration selection. */
internal object NativeMpegTags {
    fun read(data: NativeAudioBytes, start: Int, frame: NativeMpegFrame): NativeMpegTag? {
        if (frame.layer != 1) return null
        val xing = start + MPEG_HEADER + side(frame)
        val zeroed = (start + MPEG_HEADER until xing).all { data.byte(it) == 0 }
        val signature = data.ascii(xing, "Xing") || data.ascii(xing, "Info")
        val header = data.has(xing, MPEG_XING_HEADER) && signature
        return if (zeroed && header) {
            xing(data, start, xing, frame)
        } else {
            vbri(data, start, frame)
        }
    }

    private fun side(frame: NativeMpegFrame): Int = when {
        frame.version == MPEG_V1 -> if (frame.channels == 1) MPEG_SIDE_MONO_ONE else MPEG_SIDE_STEREO_ONE
        frame.channels == 1 -> MPEG_SIDE_MONO_TWO
        else -> MPEG_SIDE_MONO_ONE
    }

    private fun xing(data: NativeAudioBytes, start: Int, tag: Int, frame: NativeMpegFrame): NativeMpegTag? {
        val flags = data.big(tag + MPEG_HEADER, MPEG_HEADER)
        var offset = tag + MPEG_XING_HEADER
        val frames = if (flags and 1 != 0L && data.has(offset, MPEG_HEADER)) {
            data.big(offset, MPEG_HEADER).also { offset += MPEG_HEADER }
        } else {
            null
        }
        if (flags and 2 != 0L) offset += MPEG_HEADER
        if (flags and MPEG_XING_TOC_FLAG != 0L) offset += 100
        if (flags and MPEG_XING_QUALITY_FLAG != 0L) offset += MPEG_HEADER
        if (offset > start + frame.bytes) return null
        val samples = frames?.times(frame.samples)?.let { count ->
            (count - trim(data, start, offset, frame)).coerceAtLeast(0)
        }
        return NativeMpegTag(samples)
    }

    private fun trim(data: NativeAudioBytes, start: Int, offset: Int, frame: NativeMpegFrame): Long {
        if (offset + MPEG_LAME_MIN > start + frame.bytes) return 0
        val known = listOf("LAME", "Lavf", "Lavc").any { data.ascii(offset, it) }
        if (!known || !validCrc(data, start, offset, frame)) return 0
        val bits = data.big(offset + MPEG_LAME_TRIM_OFFSET, MPEG_LAME_TRIM_BYTES).toInt()
        val delay = MPEG_LAME_DELAY + (bits shr MPEG_LAME_TRIM_BITS)
        val padding = ((bits and MPEG_LAME_TRIM_MASK) - MPEG_LAME_DELAY).coerceAtLeast(0)
        return (delay + padding).toLong()
    }

    private fun validCrc(data: NativeAudioBytes, start: Int, offset: Int, frame: NativeMpegFrame): Boolean {
        if (offset + MPEG_LAME_FULL > start + frame.bytes) return true
        if (!frame.hasCrc && !data.ascii(offset, "LAME")) return true
        val end = offset + MPEG_LAME_FULL - 2
        val expected = data.big(end, 2)
        var crc = 0
        for (index in start until end) {
            crc = crc xor data.byte(index)
            repeat(MPEG_BYTE_BITS) {
                crc = if (crc and 1 != 0) crc shr 1 xor MPEG_CRC_POLYNOMIAL else crc shr 1
            }
        }
        return expected == 0L || expected == crc.toLong()
    }

    private fun vbri(data: NativeAudioBytes, start: Int, frame: NativeMpegFrame): NativeMpegTag? {
        val offset = start + MPEG_VBRI_OFFSET
        if (frame.bytes < MPEG_VBRI_OFFSET + MPEG_VBRI_MIN || !data.ascii(offset, "VBRI")) return null
        if ((start + MPEG_HEADER until offset).any { data.byte(it) != 0 }) return null
        return if (data.big(offset + MPEG_HEADER, 2) == 1L) {
            NativeMpegTag(data.big(offset + MPEG_VBRI_FRAMES, MPEG_HEADER) * frame.samples)
        } else {
            null
        }
    }
}
