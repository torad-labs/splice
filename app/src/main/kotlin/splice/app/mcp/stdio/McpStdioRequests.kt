// NEW: desktop-3p-parity (Nov 23, 2026). The HTTP requests the stdio MCP bridge sends a server: each carries the
// target's own headers (the MCP access key or the person's), then the headers the bridge owns, which a target header of
// the same name never replaces: the session the server gave, the protocol version it agreed, and what is sent and
// accepted. Ending a session and opening the older transport's stream are bounded, so a server that never answers
// them cannot hold the bridge open after its client has gone.
package splice.app.mcp.stdio

import splice.core.wire.HttpStatus
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class McpStdioRequests(private val target: McpStdioTarget) {
    /** The original [wire] document posted to [url], the target's own unless the older transport named another. */
    fun post(wire: String, session: McpStdioSession, url: URI = target.url): HttpRequest =
        built(HttpRequest.newBuilder(url), session)
            .setHeader(STDIO_CONTENT_TYPE, STDIO_JSON_TYPE)
            .setHeader(STDIO_ACCEPT, "$STDIO_JSON_TYPE, $STDIO_EVENT_STREAM")
            .POST(HttpRequest.BodyPublishers.ofString(wire))
            .build()

    /** The server's own stream opened, for [session]; its answer awaited no longer than [within] when given. */
    fun stream(session: McpStdioSession, within: Duration? = null): HttpRequest {
        val builder = built(HttpRequest.newBuilder(target.url), session).setHeader(STDIO_ACCEPT, STDIO_EVENT_STREAM)
        within?.let(builder::timeout)
        return builder.GET().build()
    }

    /** [session] ended, its answer awaited a bounded time. */
    fun end(session: McpStdioSession): HttpRequest =
        built(HttpRequest.newBuilder(target.url), session).timeout(STDIO_END_WITHIN).DELETE().build()

    /** Whether [response] succeeded. */
    fun ok(response: HttpResponse<InputStream>): Boolean =
        response.statusCode() in HttpStatus.OK..HttpStatus.MAX_SUCCESS

    /** Whether [response] is an event stream; media types are matched without regard to case. */
    fun streams(response: HttpResponse<InputStream>): Boolean =
        response.headers().firstValue(STDIO_CONTENT_TYPE).orElse("").startsWith(STDIO_EVENT_STREAM, ignoreCase = true)

    private fun built(builder: HttpRequest.Builder, session: McpStdioSession): HttpRequest.Builder {
        target.headers.filterKeys { it.lowercase() !in STDIO_RESTRICTED_HEADERS }.forEach { (name, value) ->
            builder.header(name, value)
        }
        session.id?.let { builder.setHeader(STDIO_SESSION_HEADER, it) }
        session.version?.let { builder.setHeader(STDIO_PROTOCOL_HEADER, it) }
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

// why: ending a session is a courtesy to the server once the client has gone; three seconds is far past a loopback
// hub's answer and still short enough that Desktop never waits on a bridge it has already let go.
private const val STDIO_END_SECONDS = 3L
private val STDIO_END_WITHIN: Duration = Duration.ofSeconds(STDIO_END_SECONDS)
