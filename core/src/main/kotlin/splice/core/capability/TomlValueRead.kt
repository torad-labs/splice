// NEW: one TOML value read as JSON, for the capability tables (CapabilityToml). :core is ktoml-free, and these
// tables need only TOML's value forms: strings, whole numbers, booleans, arrays and inline tables.
package splice.core.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val UNICODE_SHORT = 4
private const val UNICODE_LONG = 8
private const val HEX = 16
private const val SCALAR_STOPS = ",]}\n\r# \t"
private const val SPACES = " \t#"
private const val BREAKS = "\n\r"
private const val NOT_A_VALUE = "not a TOML value"

/** Reads [text], one TOML value, to its JSON shape; null when it is not one TOML value this reader knows. Inside, a
 *  malformed value fails a `require`, and [read] turns that into null: the text itself is never repeated. */
internal class TomlValueRead(private val text: String) {
    private var at = 0

    fun read(): JsonElement? = try {
        val value = value()
        skip()
        value.takeIf { at == text.length }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun value(): JsonElement {
        skip()
        return when (text.getOrNull(at)) {
            '"' -> JsonPrimitive(basic())
            '\'' -> JsonPrimitive(literal())
            '[' -> array()
            '{' -> table()
            else -> scalar()
        }
    }

    private fun basic(): String {
        require(!text.startsWith("\"\"\"", at)) { NOT_A_VALUE }
        val out = StringBuilder()
        at++
        while (true) {
            val c = text.getOrNull(at++)
            require(c != null && c != '\n') { NOT_A_VALUE }
            if (c == '"') return out.toString()
            out.append(if (c == '\\') escape() else c.toString())
        }
    }

    private fun escape(): String = when (text.getOrNull(at++)) {
        'n' -> "\n"
        't' -> "\t"
        'r' -> "\r"
        '"' -> "\""
        '\\' -> "\\"
        'u' -> code(UNICODE_SHORT)
        'U' -> code(UNICODE_LONG)
        else -> throw IllegalArgumentException(NOT_A_VALUE)
    }

    private fun code(digits: Int): String {
        val point = text.substring(at, minOf(at + digits, text.length)).toIntOrNull(HEX)
        require(point != null && Character.isValidCodePoint(point)) { NOT_A_VALUE }
        at += digits
        return String(Character.toChars(point))
    }

    private fun literal(): String {
        val end = text.indexOf('\'', at + 1)
        require(!text.startsWith("'''", at) && end > 0) { NOT_A_VALUE }
        val out = text.substring(at + 1, end)
        require('\n' !in out) { NOT_A_VALUE }
        at = end + 1
        return out
    }

    private fun array(): JsonElement {
        at++
        val items = mutableListOf<JsonElement>()
        skip(newlines = true)
        while (text.getOrNull(at) != ']') {
            items += value()
            skip(newlines = true)
            separator(']')
            skip(newlines = true)
        }
        at++
        return JsonArray(items)
    }

    private fun table(): JsonElement {
        at++
        val keys = LinkedHashMap<String, JsonElement>()
        skip()
        while (text.getOrNull(at) != '}') {
            val key = key()
            skip()
            require(text.getOrNull(at) == '=') { NOT_A_VALUE }
            at++
            keys[key] = value()
            skip()
            separator('}')
            skip()
        }
        at++
        return JsonObject(keys)
    }

    /** A comma steps over, and anything but [close] there is not a value. */
    private fun separator(close: Char) {
        if (text.getOrNull(at) == ',') at++ else require(text.getOrNull(at) == close) { NOT_A_VALUE }
    }

    private fun key(): String = when (text.getOrNull(at)) {
        '"' -> basic()
        '\'' -> literal()
        else -> {
            val start = at
            while (at < text.length && bare(text[at])) at++
            text.substring(start, at).also { require(it.isNotEmpty()) { NOT_A_VALUE } }
        }
    }

    private fun bare(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    private fun scalar(): JsonElement {
        val start = at
        while (at < text.length && text[at] !in SCALAR_STOPS) at++
        val token = text.substring(start, at).replace("_", "")
        val number = token.toLongOrNull()?.let(::JsonPrimitive) ?: token.toDoubleOrNull()?.let(::JsonPrimitive)
        return when (token) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> requireNotNull(number) { NOT_A_VALUE }
        }
    }

    /** Spaces and tabs, and in an array line breaks too. A comment runs to its line's end. */
    private fun skip(newlines: Boolean = false) {
        val stops = if (newlines) SPACES + BREAKS else SPACES
        while (at < text.length && text[at] in stops) at = past(at)
    }

    /** Past one space, or past a comment to its line's end. */
    private fun past(from: Int): Int {
        if (text[from] != '#') return from + 1
        val end = text.indexOf('\n', from)
        return if (end < 0) text.length else end
    }
}
