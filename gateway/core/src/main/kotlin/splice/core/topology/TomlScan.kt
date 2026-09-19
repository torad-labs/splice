// NEW: V4-128 — the statements of a TOML file and where each value ends, read only as far as the
// topology writer edits. Split from TopologyWriter.kt by concern (concentration, 2026-09-18); that
// file's header states what the edits are and why the output is verified.
package splice.core.topology

// why: the length of TOML's triple-quote delimiter, used to step over both ends of a multi-line
// string. Named because `start + 3` at the open and `at += 3` at the close read as two numbers.
private const val TRIPLE = 3
private const val SCALAR_STOPS = "\n\r#"
private const val BARE_PUNCTUATION = "_-"

internal enum class TomlKind { TABLE, ARRAY_TABLE, KEY }

/** One statement: its kind, its full dotted path (a key's includes its table's), the range it
 *  occupies including its line break, for a KEY the range of its value alone, and how many of the
 *  path's segments its table header contributed. */
internal data class TomlEntry(
    val kind: TomlKind,
    val path: List<String>,
    val start: Int,
    val end: Int,
    val valueStart: Int = end,
    val valueEnd: Int = end,
    val tableDepth: Int = path.size,
)

/** Where a TOML value ends, from its first character: strings, arrays and inline tables nest. */
internal class TomlValue(private val text: String) {

    fun end(start: Int): Int = when (text.getOrNull(start)) {
        '[', '{' -> nested(start)
        '"', '\'' -> string(start)
        else -> scalar(start)
    }

    fun closeQuote(start: Int, quote: Char): Int {
        var at = start + 1
        while (at < text.length && text[at] != quote) at += step(at, quote)
        return minOf(at + 1, text.length)
    }

    fun string(start: Int): Int {
        val quote = text[start]
        val triple = text.startsWith("$quote$quote$quote", start)
        return if (triple) tripleEnd(start, quote) else closeQuote(start, quote)
    }

    /** A backslash in a basic (double-quoted) string escapes the next character; literal strings have no escapes. */
    private fun step(at: Int, quote: Char): Int = if (quote == '"' && text[at] == '\\') 2 else 1

    private fun tripleEnd(start: Int, quote: Char): Int {
        val delimiter = "$quote$quote$quote"
        var at = start + TRIPLE
        while (at < text.length && !text.startsWith(delimiter, at)) at += step(at, quote)
        at += TRIPLE
        while (at < text.length && text[at] == quote) at++
        return minOf(at, text.length)
    }

    /** An array or inline table: to the bracket that closes the one at [start], stepping over strings
     *  and comments whole, since either may hold a bracket. */
    private fun nested(start: Int): Int {
        var depth = 0
        var at = start
        do {
            val char = text[at]
            at = past(at, char)
            depth += when (char) {
                '[', '{' -> 1
                ']', '}' -> -1
                else -> 0
            }
        } while (depth > 0 && at < text.length)
        return at
    }

    private fun past(at: Int, char: Char): Int = when (char) {
        '"', '\'' -> string(at)
        '#' -> text.indexOf('\n', at).let { if (it < 0) text.length else it }
        else -> at + 1
    }

    private fun scalar(start: Int): Int {
        var at = start
        while (at < text.length && text[at] !in SCALAR_STOPS) at++
        while (at > start && text[at - 1].isWhitespace()) at--
        return at
    }
}

/** The statements of a TOML file, in order. Assumes a file the loader already parsed. */
internal class TomlScan(private val text: String) {
    private val keys = TomlKeys()
    private val values = TomlValue(text)
    private var at = 0
    private var table: List<String> = emptyList()

    fun entries(): List<TomlEntry> {
        val out = mutableListOf<TomlEntry>()
        while (at < text.length) {
            val lineStart = at
            skipSpace()
            when (text.getOrNull(at)) {
                null, '\n', '\r', '#' -> at = lineEnd(at)
                '[' -> out += header(lineStart)
                else -> out += key(lineStart)
            }
        }
        return out
    }

    private fun header(lineStart: Int): TomlEntry {
        val array = text.startsWith("[[", at)
        at += if (array) 2 else 1
        table = segments()
        at = lineEnd(at)
        return TomlEntry(if (array) TomlKind.ARRAY_TABLE else TomlKind.TABLE, table, lineStart, at)
    }

    private fun key(lineStart: Int): TomlEntry {
        val path = table + segments()
        at++
        skipSpace()
        val valueStart = at
        val valueEnd = values.end(at)
        at = lineEnd(valueEnd)
        return TomlEntry(TomlKind.KEY, path, lineStart, at, valueStart, valueEnd, table.size)
    }

    private fun segments(): List<String> {
        val out = mutableListOf<String>()
        do {
            skipSpace()
            val begin = at
            at = when (text.getOrNull(at)) {
                '"', '\'' -> values.closeQuote(at, text[at])
                else -> bareEnd(at)
            }
            out += keys.unquote(text.substring(begin, at))
            skipSpace()
            val dotted = text.getOrNull(at) == '.'
            if (dotted) at++
        } while (dotted)
        return out
    }

    private fun bareEnd(start: Int): Int {
        var end = start
        while (end < text.length && isBare(text[end])) end++
        return end
    }

    private fun isBare(char: Char): Boolean = char.isLetterOrDigit() || char in BARE_PUNCTUATION

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t") at++
    }

    private fun lineEnd(from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it + 1 }
}
