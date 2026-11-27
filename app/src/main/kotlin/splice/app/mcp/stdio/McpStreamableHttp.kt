// NEW: desktop-3p-parity (Nov 23, 2026). MCP's streamable HTTP transport, as the stdio MCP bridge speaks it: each
// message posted, its answer read as the JSON it is or as the stream of events it comes in; the session and protocol
// version the server gives at `initialize` carried on every later request; the server's own stream opened once there
// is a session; the session ended when the client goes. Splice's own `/mcp/<name>` speaks this transport. A request
// the server takes without a reply to it is said to be unanswered, so the client is never left waiting on it.
package splice.app.mcp.stdio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean

internal class McpStreamableHttp(
    target: McpStdioTarget,
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
) : McpStdioTransport {
    private val requests = McpStdioRequests(target)
    private val replies = McpStdioReplies()
    private val session = McpStdioSession()
    private val listening = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var stream: InputStream? = null

    override suspend fun open(out: TerminalOutput): String? = null

    override suspend fun send(message: JsonObject, wire: String, out: TerminalOutput): McpStdioSent {
        val response = client.sendAsync(requests.post(wire, session), STREAMED).await()
        response.headers().firstValue(STDIO_SESSION_HEADER).ifPresent { session.id = it }
        return withContext(io) { answer(message, response, out) }
    }

    /** Opens the server's own stream once, when it has given a session; a server that keeps none answers otherwise.
     *  A stream that opens after the bridge closed is closed at once, and the stream is closed however this ends. */
    override suspend fun listen(out: TerminalOutput) {
        if (session.id == null || closed.get()) return
        if (!listening.compareAndSet(false, true)) return
        val response = client.sendAsync(requests.stream(session), STREAMED).await()
        val body = response.body()
        try {
            stream = body
            val open = requests.ok(response) && requests.streams(response)
            if (open && !closed.get()) {
                withContext(io) { replies.events(body.bufferedReader(), out, keep = false) }
            }
        } finally {
            body.close()
        }
    }

    override suspend fun close() {
        closed.set(true)
        stream?.close()
        if (session.id == null) return
        client.sendAsync(requests.end(session), HttpResponse.BodyHandlers.discarding()).await()
    }

    private fun answer(message: JsonObject, response: HttpResponse<InputStream>, out: TerminalOutput): McpStdioSent =
        response.body().use { body ->
            if (requests.ok(response)) {
                val answered = if (requests.streams(response)) {
                    replies.events(body.bufferedReader(), out)
                } else {
                    replies.whole(body.readAllBytes().toString(Charsets.UTF_8), out)
                }
                agreed(message, answered)
                if (replied(message, answered)) McpStdioSent.Delivered else McpStdioSent.Unanswered
            } else {
                McpStdioSent.Refused(response.statusCode())
            }
        }

    /** Whether [answered] holds the reply [message] needs: one with its id when it is a request, nothing otherwise. */
    private fun replied(message: JsonObject, answered: List<JsonObject>): Boolean {
        val id = message[STDIO_ID]
        val request = id != null && STDIO_METHOD in message
        return !request || answered.any { it[STDIO_ID] == id }
    }

    /** The protocol version the server agreed, kept from its answer to [message] when that was `initialize`. */
    private fun agreed(message: JsonObject, answered: List<JsonObject>) {
        if (JsonScalars.str(message, STDIO_METHOD) != STDIO_INITIALIZE) return
        val result = answered.firstOrNull { it[STDIO_ID] == message[STDIO_ID] }?.get("result") as? JsonObject
        JsonScalars.str(result, "protocolVersion")?.let { session.version = it }
    }
}

private val STREAMED: HttpResponse.BodyHandler<InputStream> = HttpResponse.BodyHandlers.ofInputStream()

/** A JSON-RPC message's method, id, and the method that starts an MCP session. */
internal const val STDIO_METHOD: String = "method"
internal const val STDIO_ID: String = "id"
internal const val STDIO_INITIALIZE: String = "initialize"
