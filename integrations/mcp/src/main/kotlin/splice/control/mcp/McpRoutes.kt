// NEW: v0.4.0 FEATURES.md §8 — the Streamable HTTP face of the MCP host (MCP 2025-11-25):
// POST carries one JSON-RPC message and answers with JSON (initialize mints `Mcp-Session-Id`),
// GET opens the server-to-client notification stream as text/event-stream, DELETE ends the
// session. Everything protocol-shaped lives in McpHost; this file only speaks ktor.
package splice.control.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import splice.client.mcp.MCP_CALLER_HEADER
import splice.client.mcp.MCP_HEAD_HEADER
import splice.http.SseChannelResponse
import splice.http.SseWrite
import java.nio.charset.CharacterCodingException

private const val SESSION_HEADER = "Mcp-Session-Id"
private const val PROTOCOL_HEADER = "MCP-Protocol-Version"
private const val PING_MS = 15_000L

/** Which in-process servers require the original request to be valid UTF-8. */
public fun interface McpUtf8Input {
    public operator fun invoke(server: String): Boolean
}

public class McpRoutes(
    private val host: McpHost,
    private val utf8InProcess: McpUtf8Input = McpUtf8Input { false },
) {

    public suspend fun post(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val headers = call.request.headers
        val entry = McpEntryNames(headers[MCP_HEAD_HEADER], headers[MCP_CALLER_HEADER])
        val body = try {
            document(call, name, entry)
        } catch (_: CharacterCodingException) {
            call.respondText(
                """{"jsonrpc":"2.0","id":0,"error":{"code":-32600,"message":"the body is not valid UTF-8 JSON"}}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val reply = host.post(name, headers[SESSION_HEADER], body, headers[PROTOCOL_HEADER], entry)
        reply.sessionId?.let { call.response.header(SESSION_HEADER, it) }
        val answer = reply.body
        if (answer == null) {
            call.respond(HttpStatusCode.fromValue(reply.status))
        } else {
            call.respondText(answer, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
        }
    }

    private suspend fun document(call: ApplicationCall, name: String, entry: McpEntryNames): String =
        if (utf8InProcess(name) && host.answeredHere(name, entry.head, entry.caller)) {
            call.receive<ByteArray>().decodeToString(throwOnInvalidSequence = true)
        } else {
            call.receiveText()
        }

    public suspend fun stream(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val sessionId = call.request.headers[SESSION_HEADER]
        // a server the daemon answers itself sends nothing between requests: MCP's answer for no stream is 405
        if (answeredHere(call, name)) {
            call.respond(HttpStatusCode.MethodNotAllowed)
            return
        }
        if (!host.protocolAccepted(name, sessionId, call.request.headers[PROTOCOL_HEADER])) {
            call.respondText(
                """{"error":"unsupported MCP-Protocol-Version"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val channel = host.openStream(name, sessionId)
        if (channel == null) {
            call.respondText("""{"error":"session not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        try {
            call.respond(
                SseChannelResponse { out ->
                    out.writeStringUtf8(": open\n\n")
                    out.flush()
                    pump(channel) { frame ->
                        out.writeStringUtf8(frame)
                        out.flush()
                    }
                },
            )
        } finally {
            host.closeStream(name, sessionId)
        }
    }

    public suspend fun delete(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val sessionId = call.request.headers[SESSION_HEADER]
        // nothing is kept for a client of a server the daemon answers itself, so there is nothing to end
        if (answeredHere(call, name)) {
            call.respond(HttpStatusCode.OK)
            return
        }
        if (!host.protocolAccepted(name, sessionId, call.request.headers[PROTOCOL_HEADER])) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val ended = host.endSession(name, sessionId)
        call.respond(if (ended) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }

    /** Whether [name] is a server the daemon answers itself for this client, by the head and caller its entry names. */
    private fun answeredHere(call: ApplicationCall, name: String): Boolean =
        host.answeredHere(name, call.request.headers[MCP_HEAD_HEADER], call.request.headers[MCP_CALLER_HEADER])

    /** Notifications as SSE `message` events; a comment ping keeps the connection honest while idle. */
    // select's onTimeout is the ping; kotlinx.coroutines still marks it experimental.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun pump(channel: ReceiveChannel<String>, write: SseWrite) {
        while (true) {
            val frame = select<String?> {
                channel.onReceiveCatching { it.getOrNull()?.let { text -> "event: message\ndata: $text\n\n" } ?: "" }
                onTimeout(PING_MS) { ": ping\n\n" }
            }
            if (frame.isNullOrEmpty()) return
            write(frame)
        }
    }
}
