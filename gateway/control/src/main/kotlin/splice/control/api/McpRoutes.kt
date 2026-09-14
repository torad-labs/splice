// NEW: v0.4.0 FEATURES.md §8 — the Streamable HTTP face of the MCP host (MCP 2025-11-25):
// POST carries one JSON-RPC message and answers with JSON (initialize mints `Mcp-Session-Id`),
// GET opens the server-to-client notification stream as text/event-stream, DELETE ends the
// session. Everything protocol-shaped lives in McpHost; this file only speaks ktor.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import splice.control.mcp.McpHost

private const val SESSION_HEADER = "Mcp-Session-Id"
private const val PROTOCOL_HEADER = "MCP-Protocol-Version"
private const val PING_MS = 15_000L

/** Writes one SSE frame to the client. */
internal fun interface SseWrite {
    suspend operator fun invoke(frame: String)
}

internal class McpRoutes(private val host: McpHost) {

    suspend fun post(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val headers = call.request.headers
        val reply = host.post(name, headers[SESSION_HEADER], call.receiveText(), headers[PROTOCOL_HEADER])
        reply.sessionId?.let { call.response.header(SESSION_HEADER, it) }
        val body = reply.body
        if (body == null) {
            call.respond(HttpStatusCode.fromValue(reply.status))
        } else {
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
        }
    }

    suspend fun stream(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val sessionId = call.request.headers[SESSION_HEADER]
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
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8(": open\n\n")
                flush()
                pump(channel) { frame ->
                    writeStringUtf8(frame)
                    flush()
                }
            }
        } finally {
            host.closeStream(name, sessionId)
        }
    }

    suspend fun delete(call: ApplicationCall) {
        val name = call.parameters["name"].orEmpty()
        val sessionId = call.request.headers[SESSION_HEADER]
        if (!host.protocolAccepted(name, sessionId, call.request.headers[PROTOCOL_HEADER])) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val ended = host.endSession(name, sessionId)
        call.respond(if (ended) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }

    /** Notifications as SSE `message` events; a comment ping keeps the connection honest while idle. */
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
