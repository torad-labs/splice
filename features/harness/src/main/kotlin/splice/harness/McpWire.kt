// NEW: the harness (docs/specs/harness.md, decision 13). What an MCP worker speaks over: JSON-RPC messages to a server
// and the ones it sends back, the same whether the server is a process on stdio or a URL.
package splice.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** The conversation with the server could not be kept: [message] says why, in words that never hold a key. */
internal class McpWireFailure(message: String) : IOException(message)

/** Hears each message a server sends while a request waits for its answer: a notification, or a request of its own. */
internal fun interface McpHeard {
    suspend fun heard(message: JsonObject)
}

internal interface McpWire : AutoCloseable {
    /** Sends [message], a notification or an answer to a request of the server's, which nothing comes back for. */
    suspend fun post(message: JsonObject)

    /** Sends [request] and hands every message the server sends before its answer to [heard]; the answer, or null when
     *  the server ended without one. Throws [McpWireFailure] when the request cannot be sent. */
    suspend fun ask(request: JsonObject, heard: McpHeard): JsonObject?

    /** The protocol version the server chose, which a later request names where the transport says so. */
    fun negotiated(version: String) {}
}

/** What makes a message the answer to request [id]: no method, and the same id. */
internal class McpAnswers {
    fun to(id: JsonElement?, message: JsonObject): Boolean = "method" !in message && id != null && message["id"] == id
}
