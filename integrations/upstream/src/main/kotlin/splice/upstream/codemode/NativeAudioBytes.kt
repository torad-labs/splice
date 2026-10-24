package splice.upstream.codemode

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val AUDIO_UNSIGNED_BYTE: Int = 0xff
private const val AUDIO_BYTE_BITS: Int = 8
private const val AUDIO_WORD_BYTES: Int = 4
private const val AUDIO_LONG_BYTES: Int = 8

/** Bounded container metadata reads; audio is never decoded or executed. */
internal class NativeAudioBytes(private val data: ByteArray) {
    val size: Int = data.size

    fun has(offset: Int, length: Int): Boolean = offset >= 0 && length >= 0 &&
        offset.toLong() + length <= data.size

    fun byte(offset: Int): Int = data[offset].toInt() and AUDIO_UNSIGNED_BYTE

    fun little(offset: Int, length: Int): Long {
        var value = 0L
        repeat(length) { index -> value = value or (byte(offset + index).toLong() shl (index * AUDIO_BYTE_BITS)) }
        return value
    }

    fun big(offset: Int, length: Int): Long {
        var value = 0L
        repeat(length) { index -> value = value shl AUDIO_BYTE_BITS or byte(offset + index).toLong() }
        return value
    }

    fun ascii(offset: Int, value: String): Boolean = has(offset, value.length) &&
        value.indices.all { index -> byte(offset + index) == value[index].code }

    fun float(offset: Int, length: Int): Double? {
        if (!has(offset, length)) return null
        val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)
        return when (length) {
            AUDIO_WORD_BYTES -> buffer.float.toDouble()
            AUDIO_LONG_BYTES -> buffer.double
            else -> null
        }
    }
}
