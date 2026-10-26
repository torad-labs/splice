package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement

/** Rust str whitespace and lines differ from the JVM's control-character and lone-CR rules. */
public object CodeModeText {
    /** Rust String ordering follows scalar values, including supplementary characters. */
    public val order: Comparator<String> = Comparator(::compare)

    public fun jsonValue(value: JsonElement): JsonElement = splice.upstream.codemode.json.CodeModeJson.normalize(value)

    public fun trim(value: String): String = value.trim(::whitespace)
    public fun trimStart(value: String): String = value.trimStart(::whitespace)
    public fun isBlank(value: String): Boolean = value.all(::whitespace)

    public fun lines(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val lines = value.split('\n').toMutableList()
        if (value.endsWith('\n')) lines.removeAt(lines.lastIndex)
        return lines.mapIndexed { index, line ->
            val hadNewline = index < lines.lastIndex || value.endsWith('\n')
            if (hadNewline && line.endsWith('\r')) line.dropLast(1) else line
        }
    }

    private fun whitespace(char: Char): Boolean = char in RUST_WHITESPACE

    private fun compare(left: String, right: String): Int {
        var first = 0
        var second = 0
        while (first < left.length && second < right.length) {
            val leftPoint = left.codePointAt(first)
            val rightPoint = right.codePointAt(second)
            if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
            first += Character.charCount(leftPoint)
            second += Character.charCount(rightPoint)
        }
        return (left.length - first).compareTo(right.length - second)
    }
}

private val RUST_WHITESPACE = "\u0009\u000a\u000b\u000c\u000d\u0020\u0085\u00a0\u1680\u2028\u2029\u202f\u205f\u3000"
    .toSet() + ('\u2000'..'\u200a')
