// NEW: desktop-3p-parity (Nov 23, 2026). The older HTTP+SSE MCP transport (`"type": "sse"` in Claude Code's config),
// as the stdio MCP bridge speaks it: an event stream opened at the server's URL names, in its `endpoint` event, the
// address every message is posted to, and every answer comes back on that stream. Opening is bounded: a server that
// answers no stream, or names no endpoint, within the bound is a server that did not open.
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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
    private val closed = AtomicBoolean(false)

    @Volatile
    private var stream: InputStream? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var endpoint: URI? = null

    override suspend fun open(out: TerminalOutput): String? {
        val response = client.sendAsync(requests.stream(session, SSE_OPEN_WITHIN), STREAMED).await()
        val body = response.body()
        if (!requests.ok(response) || !requests.streams(response)) {
            body.close()
            return "its event stream answered HTTP ${response.statusCode()}"
        }
        stream = body
        if (closed.get()) body.close()
        // The stream is closed if it names no endpoint in time, which ends the read below with an IOException.
        CompletableFuture.delayedExecutor(SSE_OPEN_WITHIN.toMillis(), TimeUnit.MILLISECONDS)
            .execute { if (endpoint == null) body.close() }
        val events = body.bufferedReader().also { reader = it }
        endpoint = withContext(io) { endpointOf(events) }
        return if (endpoint == null) "its event stream named no endpoint at its own address" else null
    }

    override suspend fun send(message: JsonObject, wire: String, out: TerminalOutput): McpStdioSent {
        val at = checkNotNull(endpoint) { "the bridge sends only once the stream is open" }
        val status = client.sendAsync(requests.post(wire, session, at), HttpResponse.BodyHandlers.discarding())
            .await().statusCode()
        return if (status in ACCEPTED_RANGE) McpStdioSent.Delivered else McpStdioSent.Refused(status)
    }

    override suspend fun listen(out: TerminalOutput) {
        val events = reader ?: return
        if (closed.get() || !listening.compareAndSet(false, true)) return
        withContext(io) { replies.events(events, out, keep = false) }
    }

    override suspend fun close() {
        closed.set(true)
        stream?.close()
    }

    /** Where messages go, read from [events] up to the event naming it; null when the stream ends first, or names an
     *  address that is malformed or at another origin, since the target's headers go wherever the endpoint points. */
    private fun endpointOf(events: BufferedReader): URI? {
        val sse = McpSseEvents(events)
        var event = sse.next()
        while (event != null && event.name != SSE_ENDPOINT_EVENT) event = sse.next()
        val named = event?.let {
            try {
                target.url.resolve(it.data.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return named?.takeIf(::sameOrigin)
    }

    /** Whether [at] is the target's own origin: scheme and host alike in any case, ports alike once defaulted. */
    private fun sameOrigin(at: URI): Boolean {
        val scheme = at.scheme?.lowercase()
        return scheme == target.url.scheme?.lowercase() &&
            at.host?.lowercase() == target.url.host?.lowercase() &&
            port(at, scheme) == port(target.url, scheme)
    }

    private fun port(uri: URI, scheme: String?): Int = when {
        uri.port >= 0 -> uri.port
        scheme == "https" -> SSE_HTTPS_PORT
        else -> SSE_HTTP_PORT
    }
}

private val STREAMED: HttpResponse.BodyHandler<InputStream> = HttpResponse.BodyHandlers.ofInputStream()

// The event that names where messages are posted.
private const val SSE_ENDPOINT_EVENT = "endpoint"

// The port an https URL names by its scheme alone.
private const val SSE_HTTPS_PORT = 443

// The port an http URL names by its scheme alone.
private const val SSE_HTTP_PORT = 80

// A posted message is taken with any 2xx: servers answer 202 Accepted, some 200 OK.
private val ACCEPTED_RANGE = HttpStatus.OK..HttpStatus.MAX_SUCCESS

// why: a server of this transport names its endpoint as the first event of its stream; ten seconds covers a remote
// server's cold start, and past it the client is told the server did not open rather than left waiting.
private const val SSE_OPEN_SECONDS = 10L
private val SSE_OPEN_WITHIN: Duration = Duration.ofSeconds(SSE_OPEN_SECONDS)
