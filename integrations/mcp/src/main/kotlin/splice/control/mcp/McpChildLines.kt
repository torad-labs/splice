package splice.control.mcp

import java.io.IOException
import java.io.Reader

// why: one child JSON frame is bounded before parsing or fan-out, not only after the complete line is retained.
private const val CHILD_FRAME_CHARS = 1_048_576

// why: an eight KiB read buffer bounds staging while amortizing pipe reads across ordinary MCP lines.
private const val CHILD_READ_CHARS = 8192

/** Reads newline-delimited child output without letting BufferedReader.readLine retain an unbounded frame. */
internal class McpChildLines(private val reader: Reader) {
    private val buffer = CharArray(CHILD_READ_CHARS)
    private var position = 0
    private var available = 0

    fun next(): String? {
        val line = StringBuilder()
        var code = read()
        while (code >= 0 && code != '\n'.code) {
            if (line.length >= CHILD_FRAME_CHARS) throw IOException("MCP child output frame exceeds its size limit")
            line.append(code.toChar())
            code = read()
        }
        return if (code < 0 && line.isEmpty()) null else line.toString().removeSuffix("\r")
    }

    private fun read(): Int {
        if (position >= available) {
            available = reader.read(buffer)
            position = 0
        }
        return if (available < 0) -1 else buffer[position++].code
    }
}
