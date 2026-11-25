package splice.app.control.peer

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.client.mcp.PEER_MCP_SERVER
import splice.control.mcp.inprocess.InProcessMcpHost
import splice.control.mcp.inprocess.InProcessMcpServer
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.owner.PeerOwnerProcesses

internal const val PEER_MCP_ENDPOINT = "/mcp/$PEER_MCP_SERVER"

internal class PeerMcpTools(
    private val parts: PeerParts,
    private val sends: PeerSendRoute,
    private val self: PeerSelfRoutes,
) {
    private val processes = PeerOwnerProcesses()

    /** The bearer is checked on every request; neither a holder generation nor an MCP header names a sender. */
    suspend fun post(call: ApplicationCall, principal: PeerPrincipal) {
        if (!ownsPeer(principal.name)) {
            PeerReplies.unavailable(
                HttpStatusCode.Forbidden,
                "no live auto-subscribed harness owns this peer",
            ).send(call)
            return
        }
        val host = InProcessMcpHost { name, _ -> Tools(principal).takeIf { name == PEER_MCP_SERVER } }
        val reply = checkNotNull(host.post(PEER_MCP_SERVER, null, call.receiveText(), null))
        call.respondText(reply.body.orEmpty(), ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    }

    private fun ownsPeer(name: String): Boolean {
        val owner = parts.store.nativeOwners[name] ?: return false
        val harness = parts.store.harnesses[name]
        val auto = name in parts.store.codexThreads ||
            parts.harnesses.entries.any { it.wire == harness && it.peer?.autoSubscribe == true }
        return auto && processes.live(owner)
    }

    private inner class Tools(private val principal: PeerPrincipal) : InProcessMcpServer {
        override fun tools(): JsonArray = PeerMcpWire.tools

        override suspend fun call(tool: String, arguments: JsonObject, session: String?): JsonObject =
            when (tool) {
                "list_peers" -> result(list(), false)
                "send_peer_message" -> result(sends.sendAnswer(principal, arguments))
                else -> result(self.status(principal, JsonScalars.str(arguments, "msgId").orEmpty()))
            }

        override fun resources(): JsonArray = JsonArray(emptyList())

        override fun read(uri: String): JsonArray? = null

        private fun list(): JsonObject = buildJsonObject {
            val caller = parts.store.get(principal.name)
            put("self", principal.name)
            putJsonArray("peers") {
                if (caller != null) {
                    parts.store.list().filter {
                        it.name != caller.name && parts.store.mayReach(caller, it.name, it.sessionId)
                    }.forEach { peer ->
                        add(
                            buildJsonObject {
                                put("name", peer.name)
                                put("sessionId", peer.sessionId)
                            },
                        )
                    }
                }
            }
        }

        private fun result(answer: PeerAnswer): JsonObject =
            result(answer.body, answer.status.value >= HttpStatus.BAD_REQUEST)

        private fun result(body: JsonObject, error: Boolean): JsonObject = buildJsonObject {
            put("isError", error)
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", body.toString())
                    },
                )
            }
        }
    }
}

internal object PeerMcpWire {
    val tools: JsonArray = JsonArray(
        listOf(
            tool("list_peers", "List peer identities your harness can reach."),
            tool("send_peer_message", "Send a peer-data message, not a human instruction.", "to", "text"),
            tool("peer_status", "Read your peer's send receipt by message ID.", "msgId"),
        ),
    )

    private fun tool(name: String, description: String, vararg required: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                required.forEach { prop -> putJsonObject(prop) { put("type", "string") } }
                if (name == "send_peer_message") putJsonObject("id") { put("type", "string") }
            }
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
    }
}
