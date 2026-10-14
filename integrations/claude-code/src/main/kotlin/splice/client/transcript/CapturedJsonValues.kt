package splice.client.transcript

private const val JSON_UNICODE_DIGITS = 4
private const val JSON_CONTROL_BOUNDARY = ' '
private const val JSON_START = 0
private const val JSON_KEY = 1
private const val JSON_VALUE = 2
private const val JSON_SEPARATOR = 3

/** Validates skipped JSON over a bounded reader without constructing trees or copying strings. */
internal class CapturedJsonValues(private val text: CapturedText) {
    private data class Frame(val closing: Char, var phase: Int = JSON_START)
    private var peakDepth = 0

    fun space(): Int {
        while (text.peek()?.let { it in " \t\r\n" } == true) text.take()
        return text.position
    }

    fun end(): Int {
        when (text.peek()) {
            '{', '[' -> container()
            else -> scalar()
        }
        return text.position
    }

    fun stringEnd(): Int {
        require(text.take() == '"')
        while (true) {
            val char = text.take()
            require(char >= JSON_CONTROL_BOUNDARY)
            if (char == '"') return text.position
            if (char == '\\') escaped()
        }
    }

    private fun escaped() {
        val escaped = text.take()
        if (escaped == 'u') {
            repeat(JSON_UNICODE_DIGITS) { require(text.take() in "0123456789abcdefABCDEF") }
        } else {
            require(escaped in "\"\\/bfnrt")
        }
    }

    private fun scalar() {
        when (text.peek()) {
            '"' -> stringEnd()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun number() {
        if (text.peek() == '-') text.take()
        if (text.peek() == '0') {
            text.take()
        } else {
            require(text.peek()?.let { it in '1'..'9' } == true)
            digits()
        }
        if (text.peek() == '.') {
            text.take()
            require(digit())
            digits()
        }
        if (text.peek() == 'e' || text.peek() == 'E') {
            text.take()
            if (text.peek() == '+' || text.peek() == '-') text.take()
            require(digit())
            digits()
        }
    }

    private fun digit(): Boolean = text.peek()?.let { it in '0'..'9' } == true

    private fun digits() {
        while (digit()) text.take()
    }

    private fun container() {
        val frames = ArrayDeque<Frame>()
        push(frames)
        while (frames.isNotEmpty()) {
            space()
            phase(frames, frames.last())
        }
    }

    private fun phase(frames: ArrayDeque<Frame>, current: Frame) {
        when (current.phase) {
            JSON_START -> if (text.peek() == current.closing) {
                text.take()
                frames.removeLast()
            } else {
                current.phase = firstPhase(current)
            }
            JSON_KEY -> {
                stringEnd()
                space()
                require(text.take() == ':')
                current.phase = JSON_VALUE
            }
            JSON_VALUE -> {
                current.phase = JSON_SEPARATOR
                if (text.peek() == '{' || text.peek() == '[') push(frames) else scalar()
            }
            JSON_SEPARATOR -> separator(frames, current)
            else -> error("invalid captured JSON parser phase")
        }
    }

    private fun push(frames: ArrayDeque<Frame>) {
        if (frames.size >= peakDepth) {
            peakDepth = frames.size + 1
            text.nesting(peakDepth)
        }
        frames.addLast(Frame(if (text.take() == '{') '}' else ']'))
    }

    private fun separator(frames: ArrayDeque<Frame>, current: Frame) {
        if (text.peek() == current.closing) {
            text.take()
            frames.removeLast()
        } else {
            require(text.take() == ',')
            current.phase = firstPhase(current)
        }
    }

    private fun firstPhase(current: Frame): Int = if (current.closing == '}') JSON_KEY else JSON_VALUE

    private fun literal(value: String) {
        for (char in value) require(text.take() == char)
    }
}
