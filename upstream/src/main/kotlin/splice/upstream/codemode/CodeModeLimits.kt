// NEW: shared UTF-8 ceilings keep bridge admission and worker frames in agreement.
package splice.upstream.codemode

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
        // A cap too small for any marker keeps a bare prefix — still cut on a code point (review 2:
        // take(1) on an emoji handed back an unpaired high surrogate). Otherwise the cut is solved
        // against the marker that WOULD be emitted for each candidate end: one more character kept
        // is one fewer gone, and at a digit cliff (1000 -> 999) that also narrows the marker, so
        // the total can stay flat across the cliff (reviews 3-4, 2026-09-13). Bytes never shrink and
        // the marker never widens as the prefix grows, so the feasible ends are one prefix and a
        // single walk finds the longest.
        val withMarker = maxChars >= marker(value.length).length
        val end = prefixEnd(value, maxChars, withMarker)
        return value.substring(0, end) + if (withMarker) marker(value.length - end) else ""
    }

    /** The end of the longest prefix that, with its own marker when [withMarker], fits both budgets. */
    private fun prefixEnd(value: String, maxChars: Int, withMarker: Boolean): Int {
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val next = end + Character.charCount(codePoint)
            val size = utf8Size(codePoint)
            val markerWidth = if (withMarker) marker(value.length - next).length else 0
            if (bytes + size + markerWidth > MAX_TEXT_BYTES || next + markerWidth > maxChars) break
            bytes += size
            end = next
        }
        return end
    }

    private fun marker(gone: Int): String = " [truncated $gone chars]"

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < ONE_BYTE_LIMIT -> 1
        codePoint < TWO_BYTE_LIMIT -> 2
        codePoint < THREE_BYTE_LIMIT -> THREE_BYTES
        else -> FOUR_BYTES
    }

    private const val ONE_BYTE_LIMIT = 0x80
    private const val TWO_BYTE_LIMIT = 0x800
    private const val THREE_BYTE_LIMIT = 0x10000
    private const val THREE_BYTES = 3
    private const val FOUR_BYTES = 4
}
