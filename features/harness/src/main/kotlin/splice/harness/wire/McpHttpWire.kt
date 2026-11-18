// NEW: the harness (docs/specs/harness.md, decision 13). A server reached at a URL, which is how the hub gives a session
// a server it runs for every session: MCP's streamable HTTP, each message posted, each answer read as the JSON it is or
// as the stream of events it comes in.
package splice.harness.wire

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class McpHttpWire(
    private val client: HttpClient,
    private val url: URI,
    private val headers: Map<String, String>,
    private val io: CoroutineDispatcher,
) : McpWire {
    private val answers = McpAnswers()
    private val sends = SentRequests()

    @Volatile private var session: String? = null

    @Volatile private var version: String? = null

    override fun negotiated(version: String) {
        this.version = version
    }

    override suspend fun post(message: JsonObject) {
        val body = sent(message).body()
        withContext(io) { body.use { it.readAllBytes() } }
    }

    override suspend fun ask(request: JsonObject, heard: McpHeard): JsonObject? {
        val response = sent(request)
        val body = response.body()
        try {
            val id = request["id"]
            val messages = Channel<JsonObject>(Channel.UNLIMITED)
            if (response.headers().firstValue(CONTENT_TYPE).orElse("").startsWith(EVENT_STREAM)) {
                Executors.defaultThreadFactory().newThread { events(body, messages) }.apply {
                    isDaemon = true
                    name = "harness-mcp-events"
                }.start()
            } else {
                withContext(io) { whole(body, messages) }
            }
            for (message in messages) {
                if (answers.to(id, message)) return message
                heard.heard(message)
            }
            return null
        } finally {
            body.close()
        }
    }

    /** The server's one reply to [message]: its status checked, its session kept. */
    private suspend fun sent(message: JsonObject): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, $EVENT_STREAM")
        headers.forEach { (name, value) -> builder.header(name, value) }
        session?.let { builder.header(MCP_SESSION_HEADER, it) }
        version?.let { builder.header(PROTOCOL_HEADER, it) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonWire.bytes(message))).build()
        val response = try {
            sends.response(client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()))
        } catch (_: IOException) {
            throw McpWireFailure("it could not be reached")
        }
        response.headers().firstValue(MCP_SESSION_HEADER).ifPresent { session = it }
        if (response.statusCode() !in HTTP_OK_RANGE) {
            response.body().close()
            throw McpWireFailure("it answered ${response.statusCode()}")
        }
        return response
    }

    /** An answer that is one JSON object; an empty one (a 202) has no message. */
    private fun whole(body: InputStream, into: Channel<JsonObject>) {
        try {
            val text = body.readAllBytes().toString(StandardCharsets.UTF_8).trim()
            JsonScalars.objectOrNull(lenientJson, text)?.let { into.trySend(it) }
        } catch (_: IOException) {
            // an answer that broke off has no message
        } finally {
            into.close()
        }
    }

    /** An answer that is a stream of events: each event's data lines, joined, are one message. */
    private fun events(body: InputStream, into: Channel<JsonObject>) {
        try {
            val data = StringBuilder()
            body.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (line.startsWith(SSE_DATA_PREFIX)) {
                    data.append(line.removePrefix(SSE_DATA_PREFIX).trimStart()).append('\n')
                } else if (line.isEmpty()) {
                    JsonScalars.objectOrNull(lenientJson, data.toString().trim())?.let { into.trySend(it) }
                    data.setLength(0)
                }
            }
        } catch (_: IOException) {
            // a stream that broke off ends with what it carried
        } finally {
            into.close()
        }
    }

    /** Ends the session the server gave, without waiting to hear that it did. */
    override fun close() {
        val id = session ?: return
        val request = HttpRequest.newBuilder(url).DELETE().header(MCP_SESSION_HEADER, id)
        headers.forEach { (name, value) -> request.header(name, value) }
        client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
    }
}

private const val CONTENT_TYPE = "Content-Type"
private const val EVENT_STREAM = "text/event-stream"
private const val MCP_SESSION_HEADER = "Mcp-Session-Id"
private const val PROTOCOL_HEADER = "MCP-Protocol-Version"
private const val SSE_DATA_PREFIX = "data:"
private val HTTP_OK_RANGE = HttpStatus.OK..HttpStatus.MAX_SUCCESS
