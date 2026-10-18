package splice.head.trace

import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.Reader
import kotlin.coroutines.CoroutineContext

private const val DOCUMENT_BUFFER_CHARS = 8_192
private const val DOCUMENT_MAX_DEPTH = 256
private const val DOCUMENT_UNICODE_DIGITS = 4
private const val DOCUMENT_HEX_BASE = 16
private val DOCUMENT_ESCAPES = mapOf(
    '"' to '"',
    '\\' to '\\',
    '/' to '/',
    'b' to '\b',
    'f' to '\u000c',
    'n' to '\n',
    'r' to '\r',
    't' to '\t',
)

/** A bounded cursor over the SHA-checked pack reader, never a complete body String. */
internal class TraceDocumentText(private val reader: Reader, private val context: CoroutineContext) {
    private val buffer = CharArray(DOCUMENT_BUFFER_CHARS)
    private var at = 0
    private var size = 0

    fun peek(): Char? {
        if (at == size) {
            context.ensureActive()
            size = reader.read(buffer)
            at = 0
        }
        return if (size < 0) null else buffer[at]
    }

    fun take(): Char = peek()?.also { at++ } ?: throw IOException("incomplete request document")

    fun space() {
        while (peek() in listOf(' ', '\t', '\r', '\n')) take()
    }

    fun expect(char: Char) {
        space()
        if (take() != char) throw IOException("invalid request document")
    }

    fun stringChar(): Char? = when (val char = take()) {
        '"' -> null
        '\\' -> escape()
        else -> char.takeIf { it >= ' ' } ?: throw IOException("invalid request document string")
    }

    private fun escape(): Char {
        val char = take()
        return if (char == 'u') {
            val digits = buildString { repeat(DOCUMENT_UNICODE_DIGITS) { append(take()) } }
            digits.toIntOrNull(DOCUMENT_HEX_BASE)?.toChar()
                ?: throw IOException("invalid request document Unicode escape")
        } else {
            DOCUMENT_ESCAPES[char] ?: throw IOException("invalid request document escape")
        }
    }

    fun string(limit: Int): Pair<String, Boolean> {
        expect('"')
        val result = StringBuilder()
        var complete = true
        while (true) {
            val char = stringChar() ?: break
            if (result.length < limit) result.append(char) else complete = false
        }
        if (!complete && result.lastOrNull()?.isHighSurrogate() == true) result.setLength(result.length - 1)
        return result.toString() to complete
    }

    /** Skipped containers and strings cost bounded scratch, regardless of their byte size. */
    fun skip() {
        space()
        when (peek()) {
            '"' -> string(0)
            '{', '[' -> skipContainer()
            else -> atom()
        }
    }

    private fun skipContainer() {
        val ends = CharArray(DOCUMENT_MAX_DEPTH)
        var depth = 0
        do {
            when (val char = take()) {
                '"' -> while (stringChar() != null) { /* skip without retaining text */ }
                '{', '[' -> {
                    if (depth == ends.size) throw IOException("request document is nested too deeply")
                    ends[depth++] = if (char == '{') '}' else ']'
                }
                '}', ']' -> depth = closeContainer(ends, depth, char)
            }
        } while (depth != 0)
    }

    private fun closeContainer(ends: CharArray, depth: Int, char: Char): Int {
        if (depth == 0 || ends[depth - 1] != char) throw IOException("invalid request document")
        return depth - 1
    }

    fun atom(): String {
        val result = StringBuilder()
        while (peek() != null && peek() !in listOf(' ', '\t', '\r', '\n', ',', '}', ']')) {
            if (result.length == DOCUMENT_BUFFER_CHARS) throw IOException("request document scalar is too long")
            result.append(take())
        }
        if (result.isEmpty()) throw IOException("invalid request document value")
        return result.toString()
    }
}
