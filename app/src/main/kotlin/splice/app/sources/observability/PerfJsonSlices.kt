package splice.app.sources.observability

import kotlinx.serialization.json.Json
import java.io.IOException

/** Structural slices over one bounded file; arrays never become an intermediate JSON tree. */
internal class PerfJsonSlices(private val text: String) {
    fun fields(range: IntRange): Map<String, IntRange> {
        require(text[range.first] == '{' && text[range.last] == '}')
        val result = LinkedHashMap<String, IntRange>()
        var at = whitespace(range.first + 1)
        while (at < range.last) {
            val nameEnd = end(at)
            val name = Json.decodeFromString<String>(text.substring(at, nameEnd))
            require(name.length <= MAX_NAME && result.size < MAX_FIELDS && name !in result)
            at = whitespace(nameEnd)
            require(text[at] == ':')
            at = whitespace(at + 1)
            val stop = end(at)
            result[name] = at until stop
            at = delimiter(stop, range.last)
        }
        return result
    }

    fun elements(range: IntRange): Sequence<IntRange> = sequence {
        require(text[range.first] == '[' && text[range.last] == ']')
        var at = whitespace(range.first + 1)
        while (at < range.last) {
            val stop = end(at)
            yield(at until stop)
            at = delimiter(stop, range.last)
        }
    }

    fun value(range: IntRange, maxChars: Int): String {
        if (range.last - range.first + 1 > maxChars) throw IOException("query_memory_limit")
        return text.substring(range.first, range.last + 1)
    }

    private fun delimiter(at: Int, last: Int): Int {
        val next = whitespace(at)
        if (next == last) return next
        require(text[next] == ',')
        return whitespace(next + 1).also { require(it < last) }
    }

    private fun whitespace(start: Int): Int {
        var at = start
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }

    private fun end(start: Int): Int = when (text[start]) {
        '"' -> stringEnd(start)
        '{', '[' -> containerEnd(start)
        else -> scalarEnd(start)
    }

    private fun stringEnd(start: Int): Int {
        var at = start + 1
        while (at < text.length) {
            val char = text[at++]
            if (char == '"') return at
            if (char == '\\') at++
        }
        throw IllegalArgumentException("unterminated history string")
    }

    private fun containerEnd(start: Int): Int {
        var depth = 1
        var at = start + 1
        while (at < text.length) {
            when (text[at]) {
                '"' -> at = stringEnd(at) - 1
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            at++
            if (depth == 0) return at
            require(depth <= MAX_SLICE_DEPTH)
        }
        throw IllegalArgumentException("unterminated history object")
    }

    private fun scalarChar(value: Char): Boolean = value !in ",]}" && !value.isWhitespace()

    private fun scalarEnd(start: Int): Int {
        var at = start
        while (at < text.length && scalarChar(text[at])) at++
        require(at > start)
        return at
    }
}

// Allow thirty-two fields per sliced object before building its field-offset map.
private const val MAX_FIELDS = 32

// Reject field names above sixty-four characters before retaining them in the structural index.
private const val MAX_NAME = 64

// Limit nested containers to thirty-two levels so malformed history cannot drive an unbounded structural scan.
private const val MAX_SLICE_DEPTH = 32
