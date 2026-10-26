// PORT-OF: Rust 1.99.1 core fmt::Debug for str / char::escape_debug_ext and its generated Unicode tables.
package splice.upstream.codemode.json

/** serde's Unexpected::Str uses Rust Debug, including grapheme and non-printable Unicode escapes. */
internal object CodeModeRustDebug {
    private val escapedRanges: List<IntRange> = checkNotNull(
        javaClass.getResourceAsStream(
            "/splice/upstream/codemode/json/rust-debug-escape-ranges.txt",
        ),
    ).bufferedReader().useLines { lines ->
        lines.filter { it.isNotEmpty() && !it.startsWith('#') }.map { line ->
            val (start, end) = line.split(' ')
            start.toInt(RUST_HEX_RADIX)..end.toInt(RUST_HEX_RADIX)
        }.toList()
    }

    fun string(value: String): String = buildString {
        append('"')
        value.codePoints().forEach { point -> appendPoint(this, point) }
        append('"')
    }

    private fun appendPoint(output: StringBuilder, point: Int) {
        val escaped = SHORT_ESCAPES[point]
        when {
            escaped != null -> output.append(escaped)
            escaped(point) -> output.append("\\u{${point.toString(RUST_HEX_RADIX)}}")
            else -> output.appendCodePoint(point)
        }
    }

    private fun escaped(point: Int): Boolean {
        var low = 0
        var high = escapedRanges.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val range = escapedRanges[middle]
            when {
                point < range.first -> high = middle - 1
                point > range.last -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

private const val RUST_HEX_RADIX = 16

private val SHORT_ESCAPES =
    mapOf(
        0 to "\\0",
        '\t'.code to "\\t",
        '\r'.code to "\\r",
        '\n'.code to "\\n",
        '\\'.code to "\\\\",
        '"'.code to "\\\"",
    )
