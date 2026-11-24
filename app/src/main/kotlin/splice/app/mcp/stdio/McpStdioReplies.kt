// NEW: desktop-3p-parity (Nov 23, 2026). What a server answers the stdio MCP bridge, put on its stdout: a JSON body
// (one message or a batch) or a stream of events, each message written again as one line, since stdio MCP is one
// JSON-RPC message per line and a server may answer with indented JSON.
package splice.app.mcp.stdio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonWire
import java.io.BufferedReader

internal class McpStdioReplies {
    /** [text], a JSON body, written to [out] a message per line; the messages it held. Anything but JSON objects is
     *  dropped: it is no message a client could take. */
    fun whole(text: String, out: TerminalOutput): List<JsonObject> {
        val messages = when (val parsed = parsed(text)) {
            is JsonObject -> listOf(parsed)
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            is JsonPrimitive, null -> emptyList()
        }
        messages.forEach { out.line(JsonWire.string(it)) }
        return messages
    }

    /** [reader]'s `message` events written to [out] a message per line until the stream ends; the messages when
     *  [keep] (a reply to one request, which ends), none for a stream that may never end. */
    fun events(reader: BufferedReader, out: TerminalOutput, keep: Boolean = true): List<JsonObject> {
        val events = McpSseEvents(reader)
        val messages = mutableListOf<JsonObject>()
        var event = events.next()
        while (event != null) {
            val heard = if (event.name == STDIO_SSE_DEFAULT_EVENT) whole(event.data, out) else emptyList()
            if (keep) messages += heard
            event = events.next()
        }
        return messages
    }

    /** A JSON-RPC error answering [id] with [code] and [text], as one line. */
    fun error(id: JsonElement, code: Int, text: String): String = JsonWire.string(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put(STDIO_ID, id)
            putJsonObject("error") {
                put("code", code)
                put("message", text)
            }
        },
    )

    private fun parsed(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
