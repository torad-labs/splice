package splice.upstream.codemode.json

internal enum class CodeModeStringUse { VALUE, IGNORED }

internal object CodeModeJsonString {
    fun read(
        source: String,
        start: Int,
        use: CodeModeStringUse,
    ): Pair<String, Int> = JsonStringReader(source, start, use).read()
}

/** String decoding keeps IgnoredAny's syntax checks without allocating or joining surrogate escapes. */
private class JsonStringReader(private val source: String, start: Int, private val use: CodeModeStringUse) {
    private val result = StringBuilder()
    private var cursor = start + 1
    private var invalidUnicode = false

    fun read(): Pair<String, Int> {
        while (cursor < source.length) {
            val character = source[cursor++]
            when {
                character == '"' -> return closed()
                character == '\\' -> escape()
                character < ' ' -> fail(
                    "control character (\\u0000-\\u001F) found while parsing a string",
                    if (use == CodeModeStringUse.VALUE) cursor else cursor - 1,
                )
                else -> {
                    invalidUnicode = invalidUnicode || unpaired(cursor - 1)
                    append(character)
                }
            }
        }
        fail(JSON_STRING_EOF, cursor)
    }

    private fun closed(): Pair<String, Int> {
        if (invalidUnicode && use == CodeModeStringUse.VALUE) fail("invalid unicode code point", cursor)
        return result.toString() to cursor
    }

    private fun unpaired(index: Int): Boolean = when {
        source[index].isHighSurrogate() -> source.getOrNull(index + 1)?.isLowSurrogate() != true
        source[index].isLowSurrogate() -> source.getOrNull(index - 1)?.isHighSurrogate() != true
        else -> false
    }

    private fun escape() {
        if (cursor == source.length) fail(JSON_STRING_EOF, cursor)
        when (val escaped = source[cursor++]) {
            '"', '\\', '/' -> append(escaped)
            'b', 'f', 'n', 'r', 't' -> append(checkNotNull(SHORT_ESCAPES[escaped]))
            'u' -> unicode()
            else -> failAfterByte("invalid escape", cursor - 1)
        }
    }

    private fun unicode() {
        val first = hex()
        if (use == CodeModeStringUse.IGNORED) return
        if (first !in Character.MIN_HIGH_SURROGATE.code..Character.MAX_HIGH_SURROGATE.code) {
            if (first in Character.MIN_LOW_SURROGATE.code..Character.MAX_LOW_SURROGATE.code) {
                fail("lone leading surrogate in hex escape", cursor)
            }
            result.append(first.toChar())
            return
        }
        surrogatePrefix('\\')
        surrogatePrefix('u')
        val second = hex()
        if (second !in Character.MIN_LOW_SURROGATE.code..Character.MAX_LOW_SURROGATE.code) {
            fail("lone leading surrogate in hex escape", cursor)
        }
        result.appendCodePoint(Character.toCodePoint(first.toChar(), second.toChar()))
    }

    private fun surrogatePrefix(expected: Char) {
        if (cursor == source.length) fail(JSON_STRING_EOF, cursor)
        if (source[cursor] != expected) failAfterByte("unexpected end of hex escape", cursor)
        cursor++
    }

    private fun hex(): Int {
        val digits = source.substring(cursor, (cursor + HEX_DIGITS).coerceAtMost(source.length))
        if (digits.length != HEX_DIGITS || digits.any { it !in ASCII_HEX_DIGITS }) malformedHex()
        cursor += HEX_DIGITS
        return digits.toInt(HEX_RADIX)
    }

    private fun malformedHex(): Nothing {
        if (source.substring(cursor).toByteArray(Charsets.UTF_8).size < HEX_DIGITS) {
            fail(JSON_STRING_EOF, source.length)
        }
        throw CodeModeJsonFailure("invalid escape", cursor, CodeModeJsonBytes.offset(source, cursor) + HEX_DIGITS)
    }

    private fun append(character: Char) {
        if (use == CodeModeStringUse.VALUE) result.append(character)
    }

    private fun fail(reason: String, offset: Int): Nothing = throw CodeModeJsonFailure(reason, offset)

    private fun failAfterByte(reason: String, start: Int): Nothing =
        throw CodeModeJsonFailure(reason, start, CodeModeJsonBytes.offset(source, start) + 1)
}

// why: a JSON unicode escape has exactly four hex digits (RFC 8259 section 7); serde_json 1.0.149
// src/read.rs:1075 decodes four.
private const val HEX_DIGITS = 4

// why: those escape digits are hexadecimal (RFC 8259 section 7), so they parse in base 16.
private const val HEX_RADIX = 16
private const val ASCII_HEX_DIGITS = "0123456789abcdefABCDEF"
private const val JSON_STRING_EOF = "EOF while parsing a string"

private val SHORT_ESCAPES = mapOf('b' to '\b', 'f' to '\u000C', 'n' to '\n', 'r' to '\r', 't' to '\t')
