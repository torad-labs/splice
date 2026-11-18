// NEW: the harness (docs/specs/harness.md, decision 13). A server reached at a URL, which is how the hub gives a session
// a server it runs for every session: MCP's streamable HTTP, each message posted, each answer read as the JSON it is or
// as the stream of events it comes in, and the session's own stream (McpSessionStream) heard beside them.
package splice.harness.wire

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

internal class McpHttpWire(
    private val client: HttpClient,
    private val url: URI,
    private val headers: Map<String, String>,
    private val io: CoroutineDispatcher,
) : McpWire {
    private val answers = McpAnswers()
    private val sends = SentRequests()
    private val replies = McpReplies()
    private val stream = McpSessionStream(replies)

    @Volatile private var session: String? = null

    @Volatile private var version: String? = null

    @Volatile private var listened = false

    override fun negotiated(version: String) {
        this.version = version
    }

    override suspend fun post(message: JsonObject) {
        val body = sent(message).body()
        withContext(io) { body.use { it.readAllBytes() } }
    }

    override suspend fun ask(request: JsonObject, heard: McpHeard): JsonObject? {
        listen()
        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val body = AtomicReference<InputStream?>()
        return try {
            coroutineScope {
                // A server may hold the answer's headers until the call is done (the daemon's MCP host does): what the
                // session's stream carries meanwhile is heard while the request is still being sent.
                launch { answered(request, messages, body) }
                heardUntil(request["id"], messages, heard)
            }
        } finally {
            body.get()?.close()
        }
    }

    /** Sends [request] and reads its answer into [into] on a thread of its own, keeping its [body] to close. */
    private suspend fun answered(request: JsonObject, into: Channel<JsonObject>, body: AtomicReference<InputStream?>) {
        val response = sent(request)
        val answer = response.body().also(body::set)
        if (streams(response)) replies.events(answer, into) else replies.whole(answer, into)
    }

    /** Hands [heard] what the server sends until its answer to request [id], and answers that; null when the answer
     *  ended without it. */
    private suspend fun heardUntil(id: JsonElement?, messages: Channel<JsonObject>, heard: McpHeard): JsonObject? {
        while (true) {
            val message = stream.next(messages) ?: return null
            if (answers.to(id, message)) return message
            heard.heard(message)
        }
    }

    /** Opens the session's stream once the server has given a session, and waits until it is open, so what the server
     *  sends there from then on is heard. */
    private suspend fun listen() {
        val id = session ?: return
        if (listened) return
        listened = true
        stream.opened(opened(id))
    }

    /** The body of session [id]'s stream once it is open, or null where the server keeps none: it cannot be reached
     *  for it, or answers anything but an event stream. */
    private suspend fun opened(id: String): InputStream? {
        val builder = HttpRequest.newBuilder(url).GET().header("Accept", EVENT_STREAM).header(MCP_SESSION_HEADER, id)
        headers.forEach { (name, value) -> builder.header(name, value) }
        version?.let { builder.header(PROTOCOL_HEADER, it) }
        val response = try {
            sends.response(client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()))
        } catch (_: IOException) {
            return null
        }
        if (response.statusCode() in HTTP_OK_RANGE && streams(response)) return response.body()
        response.body().close()
        return null
    }

    private fun streams(response: HttpResponse<InputStream>): Boolean =
        response.headers().firstValue(CONTENT_TYPE).orElse("").startsWith(EVENT_STREAM)

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

    /** Ends the session the server gave, without waiting to hear that it did. */
    override fun close() {
        stream.close()
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
private val HTTP_OK_RANGE = HttpStatus.OK..HttpStatus.MAX_SUCCESS
