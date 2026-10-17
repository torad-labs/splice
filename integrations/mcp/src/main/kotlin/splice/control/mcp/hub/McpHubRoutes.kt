// NEW: MCP hub steps 3 and 4 — the ktor face of McpHub: POST /api/mcp/hub/{resolve,start,listing,roots}, each a JSON
// body answered with JSON (or 204). Everything hub-shaped lives in McpHub; this file only speaks ktor.
package splice.control.mcp.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

public class McpHubRoutes(private val hub: McpHubCalls) {
    public suspend fun resolve(call: ApplicationCall) {
        answer(call, hub.resolve(call.receiveText()))
    }

    public suspend fun start(call: ApplicationCall) {
        answer(call, hub.start(call.receiveText()))
    }

    public suspend fun listing(call: ApplicationCall) {
        answer(call, hub.listing(call.receiveText()))
    }

    public suspend fun roots(call: ApplicationCall) {
        answer(call, hub.roots(call.receiveText()))
    }

    private suspend fun answer(call: ApplicationCall, reply: McpHubReply) {
        val body = reply.body
        if (body == null) {
            call.respond(HttpStatusCode.fromValue(reply.status))
        } else {
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
        }
    }
}
