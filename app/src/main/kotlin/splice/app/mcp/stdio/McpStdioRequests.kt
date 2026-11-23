// NEW: desktop-3p-parity (Nov 23, 2026). The HTTP requests the stdio MCP bridge sends a server: each carries the
// target's own headers (the MCP access key or the person's), the session the server gave and the protocol version it
// agreed, and asks for JSON or an event stream.
package splice.app.mcp.stdio

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class McpStdioRequests(private val target: McpStdioTarget) {
    /** [message] posted to [url] (the target's own unless the older transport named another). */
    fun post(message: JsonObject, session: McpStdioSession, url: URI = target.url): HttpRequest =
        built(HttpRequest.newBuilder(url), session)
            .header(STDIO_CONTENT_TYPE, STDIO_JSON_TYPE)
            .header(STDIO_ACCEPT, "$STDIO_JSON_TYPE, $STDIO_EVENT_STREAM")
            .POST(HttpRequest.BodyPublishers.ofByteArray(JsonWire.bytes(message)))
            .build()

    /** The server's own stream opened, for [session]. */
    fun stream(session: McpStdioSession): HttpRequest =
        built(HttpRequest.newBuilder(target.url), session).header(STDIO_ACCEPT, STDIO_EVENT_STREAM).GET().build()

    /** [session] ended. */
    fun end(session: McpStdioSession): HttpRequest =
        built(HttpRequest.newBuilder(target.url), session).DELETE().build()

    /** Whether [response] succeeded. */
    fun ok(response: HttpResponse<InputStream>): Boolean =
        response.statusCode() in HttpStatus.OK..HttpStatus.MAX_SUCCESS

    /** Whether [response] is an event stream. */
    fun streams(response: HttpResponse<InputStream>): Boolean =
        response.headers().firstValue(STDIO_CONTENT_TYPE).orElse("").startsWith(STDIO_EVENT_STREAM)

    private fun built(builder: HttpRequest.Builder, session: McpStdioSession): HttpRequest.Builder {
        target.headers.filterKeys { it.lowercase() !in STDIO_RESTRICTED_HEADERS }.forEach { (name, value) ->
            builder.header(name, value)
        }
        session.id?.let { builder.header(STDIO_SESSION_HEADER, it) }
        session.version?.let { builder.header(STDIO_PROTOCOL_HEADER, it) }
        return builder
    }
}

/** What a streamable HTTP server agreed with the bridge: the session it gave and the protocol version. */
internal class McpStdioSession {
    @Volatile
    var id: String? = null

    @Volatile
    var version: String? = null
}

internal const val STDIO_SESSION_HEADER: String = "Mcp-Session-Id"
private const val STDIO_PROTOCOL_HEADER = "MCP-Protocol-Version"
private const val STDIO_CONTENT_TYPE = "Content-Type"
private const val STDIO_ACCEPT = "Accept"
private const val STDIO_JSON_TYPE = "application/json"

/** The media type of an event stream. */
internal const val STDIO_EVENT_STREAM: String = "text/event-stream"

// The headers Java's HTTP client sets itself and refuses from a caller.
private val STDIO_RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")
