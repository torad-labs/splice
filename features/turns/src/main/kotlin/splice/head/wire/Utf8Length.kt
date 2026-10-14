// NEW: Oct 13, 2026 — the bytes a frame takes on the wire, for PerfKeys.BYTES_OUT ("sizes are bytes").
package splice.head.wire

/** How many bytes [String] takes in UTF-8, counted from its chars without encoding it: the frame goes to the socket as a
 *  String (RawSseWrite) and is encoded there, so no encoded copy exists here to measure, and making one per frame would
 *  encode every frame twice on the hot path. A surrogate pair is one 4-byte code point. A lone surrogate is one byte:
 *  the socket's writer is an OutputStreamWriter (Ktor's WriterContent, charset UTF-8 from SseResponse), whose JDK
 *  encoder replaces it with '?', as String.getBytes does; the hot-delta escaper passes such chars through raw. */
internal object Utf8Length {
    private const val ONE_BYTE_BELOW = 0x80
    private const val TWO_BYTES_BELOW = 0x800
    private const val THREE_BYTES = 3
    private const val LONE_SURROGATE = 1
    private const val FOUR_BYTES = 4

    fun of(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < ONE_BYTE_BELOW -> 1
                c.code < TWO_BYTES_BELOW -> 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i++
                    FOUR_BYTES
                }
                c.isSurrogate() -> LONE_SURROGATE
                else -> THREE_BYTES
            }
            i++
        }
        return bytes
    }
}
