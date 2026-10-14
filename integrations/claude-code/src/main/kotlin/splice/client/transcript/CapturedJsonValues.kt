package splice.client.transcript

private val CAPTURE_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
private const val JSON_UNICODE_DIGITS = 4
private const val JSON_CONTROL_BOUNDARY = ' '
private const val JSON_START = 0
private const val JSON_KEY = 1
private const val JSON_VALUE = 2
private const val JSON_SEPARATOR = 3

/** Validates skipped JSON without constructing its object tree, copying strings, or recursing on the JVM stack. */
internal class CapturedJsonValues(private val text: String) {
    private data class Frame(val closing: Char, var phase: Int = JSON_START)

    fun space(from: Int): Int {
        var at = from
        while (at < text.length && text[at] in " \t\r\n") at++
        return at
    }

    fun end(from: Int): Int = when (text.getOrNull(from)) {
        '{', '[' -> containerEnd(from)
        else -> scalarEnd(from)
    }

    fun stringEnd(from: Int): Int {
        require(text.getOrNull(from) == '"')
        var at = from + 1
        while (at < text.length) {
            val char = text[at++]
            require(char >= JSON_CONTROL_BOUNDARY)
            if (char == '"') return at
            if (char == '\\') at = escapedEnd(at)
        }
        error("unterminated captured JSON string")
    }

    private fun escapedEnd(from: Int): Int {
        val escaped = text.getOrNull(from)
        require(escaped != null)
        if (escaped != 'u') {
            require(escaped in "\"\\/bfnrt")
            return from + 1
        }
        val end = from + 1 + JSON_UNICODE_DIGITS
        require(end <= text.length)
        for (at in from + 1 until end) require(text[at] in "0123456789abcdefABCDEF")
        return end
    }

    private fun scalarEnd(from: Int): Int = when (text.getOrNull(from)) {
        '"' -> stringEnd(from)
        't' -> literal(from, "true")
        'f' -> literal(from, "false")
        'n' -> literal(from, "null")
        else -> (CAPTURE_NUMBER.matchAt(text, from)?.range?.last ?: error("invalid captured JSON")) + 1
    }

    private fun containerEnd(from: Int): Int {
        val frames = ArrayDeque<Frame>()
        frames.addLast(frame(text[from]))
        var at = from + 1
        while (frames.isNotEmpty()) {
            at = space(at)
            val current = frames.last()
            when (current.phase) {
                JSON_START -> {
                    if (text.getOrNull(at) == current.closing) {
                        frames.removeLast()
                        at++
                    } else {
                        current.phase = firstPhase(current)
                    }
                }
                JSON_KEY -> {
                    at = space(stringEnd(at))
                    require(text.getOrNull(at) == ':')
                    at++
                    current.phase = JSON_VALUE
                }
                JSON_VALUE -> at = valueEnd(frames, current, at)
                JSON_SEPARATOR -> at = separator(frames, current, at)
                else -> error("invalid captured JSON parser phase")
            }
        }
        return at
    }

    private fun valueEnd(frames: ArrayDeque<Frame>, current: Frame, at: Int): Int {
        current.phase = JSON_SEPARATOR
        val char = text.getOrNull(at)
        return if (char == '{' || char == '[') {
            frames.addLast(frame(char))
            at + 1
        } else {
            scalarEnd(at)
        }
    }

    private fun separator(frames: ArrayDeque<Frame>, current: Frame, at: Int): Int {
        if (text.getOrNull(at) == current.closing) {
            frames.removeLast()
        } else {
            require(text.getOrNull(at) == ',')
            current.phase = firstPhase(current)
        }
        return at + 1
    }

    private fun firstPhase(current: Frame): Int = if (current.closing == '}') JSON_KEY else JSON_VALUE

    private fun frame(opening: Char): Frame = Frame(if (opening == '{') '}' else ']')

    private fun literal(from: Int, value: String): Int {
        require(text.startsWith(value, from))
        return from + value.length
    }
}
