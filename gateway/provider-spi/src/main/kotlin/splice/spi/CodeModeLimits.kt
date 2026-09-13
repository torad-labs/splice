// NEW: shared UTF-8 ceilings keep bridge admission and worker frames in agreement.
package splice.spi

/** Hard byte ceilings for code-mode text and its serialized worker protocol. */
public object CodeModeLimits {
    public const val MAX_TEXT_BYTES: Int = 65_536
    public const val MAX_FRAME_BYTES: Int = 1_048_576

    /** Whether [value] fits the worker's UTF-8 text limit, independently of character limits. */
    public fun fitsText(value: String): Boolean = value.encodeToByteArray().size <= MAX_TEXT_BYTES

    /** [value] itself when it fits [maxChars] and [MAX_TEXT_BYTES]; otherwise the longest prefix that
     *  leaves room for the marker — cut on a code point, never inside a surrogate pair — followed by
     *  ` [truncated N chars]`, the marker 0.3.2 readers already know from interrupted outputs.
     *  Admission truncates instead of rejecting (v0.4.0, FEATURES.md §11): Claude Code cannot send a
     *  corrected result, so a rejected 70 KiB Read was a dead turn. */
    public fun boundedText(value: String, maxChars: Int = Int.MAX_VALUE): String {
        if (value.length <= maxChars && fitsText(value)) return value
        val byteBudget = MAX_TEXT_BYTES - MARKER_RESERVE
        val charBudget = (maxChars - MARKER_RESERVE).coerceAtLeast(0)
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val width = Character.charCount(codePoint)
            val size = utf8Size(codePoint)
            if (bytes + size > byteBudget || end + width > charBudget) break
            bytes += size
            end += width
        }
        return value.substring(0, end) + " [truncated ${value.length - end} chars]"
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < ONE_BYTE_LIMIT -> 1
        codePoint < TWO_BYTE_LIMIT -> 2
        codePoint < THREE_BYTE_LIMIT -> THREE_BYTES
        else -> FOUR_BYTES
    }

    private const val MARKER_RESERVE = 40
    private const val ONE_BYTE_LIMIT = 0x80
    private const val TWO_BYTE_LIMIT = 0x800
    private const val THREE_BYTE_LIMIT = 0x10000
    private const val THREE_BYTES = 3
    private const val FOUR_BYTES = 4
}
