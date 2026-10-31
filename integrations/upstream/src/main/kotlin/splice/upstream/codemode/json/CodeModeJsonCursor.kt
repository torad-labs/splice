package splice.upstream.codemode.json

/** One UTF-16 cursor reports the byte columns of the original UTF-8 serde input. */
internal class CodeModeJsonCursor(val source: String) {
    var position = 0

    fun peek(): Char? {
        while (position < source.length && source[position] in JSON_WHITESPACE) position++
        return source.getOrNull(position)
    }

    fun take(): Char = source[position++]
    fun nextOffset(): Int = if (peek() == null) position else position + 1
    fun fail(reason: String, offset: Int = position): Nothing = throw CodeModeJsonFailure(reason, offset)

    fun end() {
        if (peek() != null) fail("trailing characters", position + 1)
    }

    fun message(error: CodeModeJsonFailure): String {
        error.byteOffset?.let {
            val (line, column) = CodeModeJsonBytes.location(source, it)
            return "${error.reason} at line $line column $column"
        }
        val prefix = source.take(error.offset.coerceAtMost(source.length))
        val line = prefix.count { it == '\n' } + 1
        val lastLine = prefix.substringAfterLast('\n')
        val lastPoint = lastLine.codePoints().toArray().lastOrNull()
        val byteWidth = lastLine.toByteArray(Charsets.UTF_8).size
        val column = if (peekedPoint(error, lastPoint)) {
            byteWidth - Character.toString(checkNotNull(lastPoint)).toByteArray(Charsets.UTF_8).size + 1
        } else {
            byteWidth
        }
        return "${error.reason} at line $line column $column"
    }

    private fun peekedPoint(error: CodeModeJsonFailure, lastPoint: Int?): Boolean {
        if (lastPoint == null || error.offset != position + 1) return false
        return source.getOrNull(error.offset - 1)?.code == lastPoint
    }
}

private const val JSON_WHITESPACE = " \r\n\t"
