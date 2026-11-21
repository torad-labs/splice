// NEW: gateway-no-heads. One line of splice.toml read the way a fix needs it: where a character sits outside a quoted
// string, where an inline table closes, and what a dotted key names. Nothing here parses TOML; it answers the few
// questions a move asks of a line it must not misread, and says "not found" for anything it cannot place.
package splice.app.cli

// why: the character no TOML text holds, standing for "not inside a quoted string".
private const val NO_QUOTE = '\u0000'

internal class TomlLine(private val text: String) {
    /** Which characters sit inside a quoted string (basic or literal), the quotes included. */
    private val quoted: BooleanArray = BooleanArray(text.length).also { marks ->
        var quote = NO_QUOTE
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (quote != NO_QUOTE) {
                marks[at] = true
                if (char == '\\' && quote == '"') {
                    at++
                    if (at < text.length) marks[at] = true
                } else if (char == quote) {
                    quote = NO_QUOTE
                }
            } else if (char == '"' || char == '\'') {
                quote = char
                marks[at] = true
            }
            at++
        }
    }

    /** The first [wanted] at or after [from] that is outside a quoted string, or -1. */
    fun indexOutsideQuotes(wanted: Char, from: Int = 0): Int {
        for (at in from until text.length) {
            if (!quoted[at] && text[at] == wanted) return at
        }
        return -1
    }

    /** The `}` that closes the `{` at [open], counting inline tables inside it, or -1 when the line never closes it. */
    fun closingBrace(open: Int): Int {
        var depth = 0
        for (at in open until text.length) {
            if (quoted[at]) continue
            if (text[at] == '{') depth++
            if (text[at] == '}' && --depth == 0) return at
        }
        return -1
    }

    /** The names a dotted key spells, unquoted: `providers."a.b".overrides` is providers, a.b, overrides. */
    fun dotted(): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for (at in text.indices) {
            if (!quoted[at] && text[at] == '.') {
                parts += text.substring(start, at)
                start = at + 1
            }
        }
        parts += text.substring(start)
        return parts.map { unquoted(it.trim()) }
    }

    private fun unquoted(name: String): String {
        val quote = name.firstOrNull()?.takeIf { it in "\"'" } ?: return name
        return if (name.length > 1 && name.last() == quote) name.substring(1, name.length - 1) else name
    }
}
