// The byte buffer both hub binaries pump through: appended at the end, consumed from the front.
package splice.hub.io

/** A growable byte buffer. [data] holds [size] live bytes from index 0. */
class Bytes {
    var data = ByteArray(INITIAL)
        private set
    var size = 0
        private set

    fun append(src: ByteArray, n: Int = src.size, from: Int = 0) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        src.copyInto(data, size, from, from + n)
        size += n
    }

    fun append(text: String) = append(text.encodeToByteArray())

    fun drop(n: Int) {
        data.copyInto(data, 0, n, size)
        size -= n
    }

    fun clear() {
        size = 0
    }

    /** The bytes from [from] on, as their own buffer. */
    fun tail(from: Int): Bytes = Bytes().also { it.append(data, size - from, from) }

    /** The index after the last newline in the first [limit] bytes, or 0 when there is none. */
    fun wholeLines(limit: Int = size): Int {
        for (i in limit - 1 downTo 0) if (data[i] == NEWLINE) return i + 1
        return 0
    }

    fun indexOf(pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    fun text(from: Int, to: Int): String = data.decodeToString(from, to)
}

private const val INITIAL = 16 * 1024
private const val NEWLINE: Byte = 10
