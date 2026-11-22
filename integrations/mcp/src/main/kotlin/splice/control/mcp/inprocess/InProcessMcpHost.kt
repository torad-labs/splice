// NEW: modes-mcp (Nov 21, 2026) — the MCP protocol for a server answered in process (InProcessMcp.kt). One POST is
// one JSON-RPC message answered at once: there is no child to forward to, no id to remap and nothing a client's session
// has to be remembered for, so any `Mcp-Session-Id` is taken as it comes (McpHost.kt, V4-148: a client told its
// session is unknown stops using the server for the rest of its life).
package splice.control.mcp.inprocess

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpReply
import splice.control.mcp.RpcKind
import splice.core.GATEWAY_VERSION
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import java.util.UUID

// why: JSON-RPC 2.0's code for a body that is not a request object.
private const val RPC_NOT_A_REQUEST = -32600

// why: JSON-RPC 2.0's code for a method the server does not have.
private const val RPC_NO_SUCH_METHOD = -32601

// why: JSON-RPC 2.0's code for parameters a method cannot take, an unknown tool's name among them.
private const val RPC_BAD_PARAMS = -32602

// why: MCP's code for a resource a server does not have.
private const val RPC_NO_SUCH_RESOURCE = -32002

/** Answers a client's POST for a server [servers] holds, and nothing for a name it does not hold. */
internal class InProcessMcpHost(private val servers: InProcessMcp, private val codec: JsonRpcCodec) {
    // the protocol revisions spoken here, newest first: a client that asks for one of them gets it back
    private val spoken = listOf("2025-11-25", "2025-06-18", "2025-03-26")

    /** Whether a server called [name] is answered here for a client on [head]. */
    fun answers(name: String, head: String?): Boolean = servers.server(name, head) != null

    /** The reply to one POSTed [body] for [name], or null when no server of that name is answered here for [head]. */
    fun post(name: String, sessionId: String?, body: String, head: String?): McpReply? {
        val server = servers.server(name, head) ?: return null
        val message = codec.parse(body)
            ?: return refusal(JsonPrimitive(0), RPC_NOT_A_REQUEST, "not a JSON-RPC object")
        return when (codec.kind(message)) {
            RpcKind.REQUEST -> answer(Asked(name, server, sessionId), message)
            // a notification (initialized, cancelled) or a response to nothing asked: taken, and nothing to say back
            RpcKind.NOTIFICATION, RpcKind.RESPONSE -> McpReply(HttpStatus.ACCEPTED, null)
            RpcKind.INVALID -> refusal(message["id"] ?: JsonPrimitive(0), RPC_NOT_A_REQUEST, "invalid JSON-RPC")
        }
    }

    /** Who is asked: the server's name, the server, and the client's session. */
    private data class Asked(val name: String, val server: InProcessMcpServer, val session: String?)

    private fun answer(asked: Asked, message: JsonObject): McpReply {
        val id = message.getValue("id")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (val method = codec.method(message)) {
            "initialize" -> ok(id, initialized(asked, params), asked.session ?: UUID.randomUUID().toString())
            "ping" -> ok(id, buildJsonObject {})
            "tools/list" -> ok(id, buildJsonObject { put("tools", asked.server.tools()) })
            "tools/call" -> called(asked, id, params)
            else -> resource(asked.server, id, params, method)
        }
    }

    /** The resource methods, and the refusal of a method this host does not speak. */
    private fun resource(server: InProcessMcpServer, id: JsonElement, params: JsonObject, method: String): McpReply =
        when (method) {
            "resources/list" -> ok(id, buildJsonObject { put("resources", server.resources()) })
            "resources/templates/list" -> ok(id, buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) })
            "resources/read" -> read(server, id, params)
            else -> failed(id, RPC_NO_SUCH_METHOD, "Method not found: $method")
        }

    private fun initialized(asked: Asked, params: JsonObject): JsonObject = buildJsonObject {
        val wanted = JsonScalars.str(params, "protocolVersion")
        put("protocolVersion", wanted?.takeIf { it in spoken } ?: spoken.first())
        put(
            "capabilities",
            buildJsonObject {
                put("tools", buildJsonObject {})
                put("resources", buildJsonObject {})
            },
        )
        put(
            "serverInfo",
            buildJsonObject {
                put("name", asked.name)
                put("version", GATEWAY_VERSION)
            },
        )
        asked.server.instructions()?.let { put("instructions", it) }
    }

    private fun called(asked: Asked, id: JsonElement, params: JsonObject): McpReply {
        val tool = JsonScalars.str(params, "name")
        val known = asked.server.tools().any { JsonScalars.str(it as? JsonObject ?: return@any false, "name") == tool }
        if (tool == null || !known) return failed(id, RPC_BAD_PARAMS, "Unknown tool: ${tool.orEmpty()}")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return ok(id, asked.server.call(tool, arguments, asked.session))
    }

    private fun read(server: InProcessMcpServer, id: JsonElement, params: JsonObject): McpReply {
        val uri = JsonScalars.str(params, "uri") ?: return failed(id, RPC_BAD_PARAMS, "resources/read names no uri")
        val contents = server.read(uri) ?: return failed(id, RPC_NO_SUCH_RESOURCE, "Resource not found: $uri")
        return ok(id, buildJsonObject { put("contents", contents) })
    }

    private fun ok(id: JsonElement, result: JsonObject, session: String? = null): McpReply =
        McpReply(HttpStatus.OK, codec.encode(codec.result(id, result)), session)

    /** A request the server could not serve: a JSON-RPC error under the request's id, on a 200 as MCP sends them. */
    private fun failed(id: JsonElement, code: Int, said: String): McpReply =
        McpReply(HttpStatus.OK, codec.encode(codec.error(id, code, said)))

    /** A body that is not a JSON-RPC message at all. */
    private fun refusal(id: JsonElement, code: Int, said: String): McpReply =
        McpReply(HttpStatus.BAD_REQUEST, codec.encode(codec.error(id, code, said)))
}
