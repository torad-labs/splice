// NEW: RFC 3986 percent-encoding + x-www-form-urlencoded body builder — the single copy shared by
// every OAuth flow. codex/grok/kimi each carried a byte-identical private copy (verified 2026-07-18);
// this is the one source. #924 Phase 3.
package splice.core.util

public object FormEncoding {
    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 0x80

    /** RFC 3986 percent-encoding — spaces become %20, never + (the CLI-parity gotcha). */
    public fun percentEncode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and BYTE_MASK
            val ch = c.toChar()
            val unreserved = ch.isLetterOrDigit() && c < ASCII_LIMIT
            if (unreserved || ch in "-_.~") append(ch) else append("%%%02X".format(c))
        }
    }

    /** x-www-form-urlencoded body: RFC3986-encoded values joined by &. */
    public fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
}
