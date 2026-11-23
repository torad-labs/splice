// NEW: desktop-3p-parity (Nov 23, 2026). The older HTTP+SSE MCP transport (`"type": "sse"` in Claude Code's config),
// as the stdio MCP bridge speaks it: an event stream opened at the server's URL names, in its `endpoint` event, the
// address every message is posted to, and every answer comes back on that stream.
package splice.app.mcp.stdio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.wire.HttpStatus
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean

internal class McpLegacySse(
    private val target: McpStdioTarget,
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
) : McpStdioTransport {
    private val requests = McpStdioRequests(target)
    private val replies = McpStdioReplies()
    private val session = McpStdioSession()
    private val listening = AtomicBoolean(false)

    @Volatile
    private var stream: InputStream? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var endpoint: URI? = null

    override suspend fun open(out: TerminalOutput): String? {
        val response = client.sendAsync(requests.stream(session), HttpResponse.BodyHandlers.ofInputStream()).await()
        val body = response.body()
        if (!requests.ok(response) || !requests.streams(response)) {
            body.close()
            return "its event stream answered HTTP ${response.statusCode()}"
        }
        stream = body
        val events = body.bufferedReader().also { reader = it }
        endpoint = withContext(io) { endpointOf(events) }
        return if (endpoint == null) "its event stream named no endpoint at its own address" else null
    }

    override suspend fun send(message: JsonObject, out: TerminalOutput): McpStdioSent {
        val at = checkNotNull(endpoint) { "the bridge sends only once the stream is open" }
        val status = client.sendAsync(requests.post(message, session, at), HttpResponse.BodyHandlers.discarding())
            .await().statusCode()
        return if (status in ACCEPTED_RANGE) McpStdioSent.Delivered else McpStdioSent.Refused(status)
    }

    override suspend fun listen(out: TerminalOutput) {
        val events = reader ?: return
        if (!listening.compareAndSet(false, true)) return
        withContext(io) { replies.events(events, out, keep = false) }
    }

    override suspend fun close() {
        stream?.close()
    }

    /** Where messages go, read from [events] up to the event naming it; null when the stream ends first or names
     *  another origin, since the target's headers go wherever the endpoint points. */
    private fun endpointOf(events: BufferedReader): URI? {
        val sse = McpSseEvents(events)
        var event = sse.next()
        while (event != null && event.name != SSE_ENDPOINT_EVENT) event = sse.next()
        return event?.let { target.url.resolve(it.data.trim()) }?.takeIf(::sameOrigin)
    }

    private fun sameOrigin(at: URI): Boolean =
        at.scheme == target.url.scheme && at.host == target.url.host && at.port == target.url.port
}

// The event that names where messages are posted.
private const val SSE_ENDPOINT_EVENT = "endpoint"

// A posted message is taken with any 2xx: servers answer 202 Accepted, some 200 OK.
private val ACCEPTED_RANGE = HttpStatus.OK..HttpStatus.MAX_SUCCESS
