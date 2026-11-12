package splice.core.memory

/** Two scalar words keep overlapping estimates removable after their published sum saturates. */
internal class HeapByteCount {
    // why: each word represents a nonnegative base-2^63 digit without overflowing signed arithmetic.
    private var whole = 0L
    private var remainder = 0L

    val value: Long get() = if (whole == 0L) remainder else Long.MAX_VALUE

    fun adjust(bytes: Long, direction: Long) {
        if (direction > 0L) {
            add(bytes)
        } else {
            remove(bytes)
        }
    }

    private fun add(bytes: Long) {
        val room = Long.MAX_VALUE - remainder
        if (bytes > room) {
            whole++
            remainder = bytes - room - 1L
        } else {
            remainder += bytes
        }
    }

    private fun remove(bytes: Long) {
        if (bytes > remainder) {
            whole--
            remainder = Long.MAX_VALUE - (bytes - remainder) + 1L
        } else {
            remainder -= bytes
        }
    }
}
