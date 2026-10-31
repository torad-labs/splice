package splice.upstream.codemode.json

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Invalid UTF-8 bytes stay one-byte markers until the string visitor decides whether to validate them. */
internal object CodeModeJsonBytes {
    fun decode(source: ByteArray): String {
        val input = ByteBuffer.wrap(source)
        val output = CharBuffer.allocate(source.size)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        while (true) {
            val decoded = decoder.decode(input, output, true)
            if (decoded.isUnderflow) return output.flip().toString()
            check(decoded.isError)
            repeat(decoded.length()) { output.put(Character.MIN_LOW_SURROGATE) }
            input.position(input.position() + decoded.length())
        }
    }

    fun offset(source: String, end: Int): Int = source.substring(0, end).toByteArray(Charsets.UTF_8).size

    fun location(source: String, offset: Int): Pair<Int, Int> {
        val bytes = source.toByteArray(Charsets.UTF_8)
        var line = 1
        var column = 0
        for (index in 0 until offset.coerceAtMost(bytes.size)) {
            if (bytes[index] == '\n'.code.toByte()) {
                line++
                column = 0
            } else {
                column++
            }
        }
        return line to column
    }
}
