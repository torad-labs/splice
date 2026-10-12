// NEW: Oct 11, 2026 — a growable byte writer and a cursor over bytes, both with variable-length integers.
package splice.app.sources

// A seven-bit group per byte, the eighth bit announcing another one.
private const val GROUP_BITS = 7
private const val GROUP_MASK = 0x7FL
private const val MORE = 0x80

/** Appends bytes, growing as it goes; [clear] reuses the buffer. */
internal class PerfBytes(initial: Int = 256) {
    private var data = ByteArray(initial)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun put(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value.toByte()
    }

    fun putVarint(value: Long) {
        var rest = value
        while (rest and GROUP_MASK.inv() != 0L) {
            put(((rest and GROUP_MASK).toInt()) or MORE)
            rest = rest ushr GROUP_BITS
        }
        put(rest.toInt())
    }

    fun putZigzag(value: Long) = putVarint((value shl 1) xor (value shr (Long.SIZE_BITS - 1)))

    fun putAll(bytes: ByteArray) {
        bytes.forEach { put(it.toInt()) }
    }

    fun toArray(): ByteArray = data.copyOf(size)
}

/** Reads what [PerfBytes] wrote, from [position]. */
internal class PerfByteCursor(private val data: ByteArray, var position: Int) {
    fun get(): Int = data[position++].toInt() and UByte.MAX_VALUE.toInt()

    fun varint(): Long {
        var value = 0L
        var shift = 0
        do {
            val next = get()
            value = value or ((next.toLong() and GROUP_MASK) shl shift)
            shift += GROUP_BITS
        } while (next and MORE != 0)
        return value
    }

    fun zigzag(): Long {
        val value = varint()
        return (value ushr 1) xor -(value and 1L)
    }

    fun bytes(count: Int): ByteArray = data.copyOfRange(position, position + count).also { position += count }
}
