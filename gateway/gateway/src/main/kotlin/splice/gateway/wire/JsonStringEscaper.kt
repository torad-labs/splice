// NEW: RFC 8259 escaper split out of SseEmitter.kt (concentration campaign, HD-24) — a stateless
// algorithm with zero references to seal state, block index or frame ownership. Neither
// kt-l3-sole-wire-terminals nor kt-l3-end-turn-literal's regex touches it: it emits `\uXXXX` and
// the six short escapes, never message_stop/message_delta/end_turn.
package splice.gateway.wire

// JSON string-escape shapes (RFC 8259): controls below 0x20 escape as \u four-hex-digits.
private const val CONTROL_CHAR_BOUND = 0x20
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

// Control-char code points kotlinx short-escapes (named so the escaper carries no magic numbers).
private const val CH_BACKSPACE = 0x08
private const val CH_TAB = 0x09
private const val CH_NEWLINE = 0x0A
private const val CH_FORMFEED = 0x0C
private const val CH_RETURN = 0x0D

/**
 * RFC 8259 string escaping for [SseFrameWriter]'s hot-delta path — the value payload of a
 * content_block_delta only. MUST stay byte-identical to kotlinx-serialization's escaper (L3: the
 * golden differential diffs bytes): kotlinx emits the SHORT forms for backspace (0x08) and form
 * feed (0x0C); the \uXXXX forms would be valid JSON but a byte divergence (audit 2026-07-18; the
 * whole control range is pinned by SseEscapingParityTest). That byte-parity contract travels with
 * this code, not with its location: SseEscapingParityTest pins it through SseEmitterFactory.create
 * plus public frame output, never by reflecting into this class directly.
 */
internal class JsonStringEscaper {
    internal fun appendJsonEscaped(sink: StringBuilder, s: String) {
        for (i in s.indices) {
            val c = s[i]
            val shortEsc = shortEscape(c)
            when {
                shortEsc != null -> sink.append(shortEsc)
                c.code < CONTROL_CHAR_BOUND -> appendUnicodeEscape(sink, c)
                else -> sink.append(c)
            }
        }
    }

    /** The six two-char JSON escapes kotlinx emits; null = not a short-escaped char. */
    private fun shortEscape(c: Char): String? = when (c.code) {
        '\\'.code -> "\\\\"
        '"'.code -> "\\\""
        CH_NEWLINE -> "\\n"
        CH_RETURN -> "\\r"
        CH_TAB -> "\\t"
        CH_BACKSPACE -> "\\b"
        CH_FORMFEED -> "\\f"
        else -> null
    }

    private fun appendUnicodeEscape(sink: StringBuilder, c: Char) {
        sink.append("\\u")
        val hex = c.code.toString(HEX_RADIX)
        repeat(UNICODE_ESCAPE_DIGITS - hex.length) { sink.append('0') }
        sink.append(hex)
    }
}
