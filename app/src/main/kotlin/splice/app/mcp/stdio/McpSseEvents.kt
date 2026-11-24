// NEW: desktop-3p-parity (Nov 23, 2026). A Server-Sent Events stream read one event at a time, for the stdio MCP
// bridge (`splice mcp stdio`): the `event:` name (`message` when it is absent or empty) and its `data:` lines joined,
// complete only at the blank line that ends it. Comments and other fields are skipped, an event with no data is no
// event, and one the stream cut off before its blank line is not dispatched.
package splice.app.mcp.stdio

import java.io.BufferedReader

/** One event from an SSE stream: its name (`message` where the stream names none) and its data. */
internal data class McpSseEvent(val name: String, val data: String)

/** [reader] read as SSE. */
internal class McpSseEvents(private val reader: BufferedReader) {
    /** The next complete event, or null when the stream ended first. */
    fun next(): McpSseEvent? {
        val pending = Pending()
        var line = reader.readLine()
        while (line != null && !pending.take(line)) line = reader.readLine()
        return if (line == null) null else pending.event()
    }

    /** The event being read: what its lines said so far. */
    private class Pending {
        private var name = STDIO_SSE_DEFAULT_EVENT
        private val data = StringBuilder()
        private var hasData = false

        /** [line] taken in; true when it ends an event that carries data. */
        fun take(line: String): Boolean {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when {
                line.isEmpty() -> if (!hasData) name = STDIO_SSE_DEFAULT_EVENT
                line.startsWith(':') -> Unit
                field == "event" -> name = value.ifEmpty { STDIO_SSE_DEFAULT_EVENT }
                field == "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
            return line.isEmpty() && hasData
        }

        fun event(): McpSseEvent? = if (hasData) McpSseEvent(name, data.toString()) else null
    }
}

/** The name SSE gives an event its stream does not name. */
internal const val STDIO_SSE_DEFAULT_EVENT: String = "message"
