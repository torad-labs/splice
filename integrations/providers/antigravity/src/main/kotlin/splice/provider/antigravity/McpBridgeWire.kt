// HTTP and JSON-RPC wire helpers for McpBridge (agy-desktop-parity-2).
package splice.provider.antigravity

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.wire.HttpStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class McpBridgeWire(
    private val expectedAuth: ByteArray,
    private val servers: Set<String>,
) {
    fun validate(exchange: HttpExchange): String? {
        val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(StandardCharsets.UTF_8)
        val name = exchange.requestURI.path.removePrefix(MCP_WIRE_PREFIX)
        val errorStatus = when {
            !exchange.requestMethod.equals("POST", ignoreCase = true) -> METHOD_NOT_ALLOWED_CODE
            !MessageDigest.isEqual(auth, expectedAuth) -> HttpStatus.UNAUTHORIZED
            !isKnownServer(name) -> HttpStatus.NOT_FOUND
            else -> null
        }
        if (errorStatus != null) send(exchange, errorStatus, "")
        return if (errorStatus == null) name else null
    }

    fun controlRequest(requestId: String, serverName: String, message: JsonObject): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") {
            put("subtype", "mcp_message")
            put("server_name", serverName)
            put("message", message)
        }
    }

    fun rpcError(rpcId: JsonElement, message: String): JsonObject {
        val details = buildJsonObject {
            put("code", BRIDGE_RPC_ERROR)
            put("message", message)
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put(BRIDGE_ERROR_FIELD, details)
        }
    }

    fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isNotEmpty()) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } else {
            exchange.sendResponseHeaders(status, -1)
        }
    }

    private fun isKnownServer(name: String): Boolean = name.isNotEmpty() && '/' !in name && name in servers
}

internal const val MCP_WIRE_PREFIX: String = "/mcp/"

// why: JSON-RPC 2.0 server-error code returned when Desktop rejects an mcp_message request.
private const val BRIDGE_RPC_ERROR = -32000

private const val BRIDGE_ERROR_FIELD = "error"

// why: HTTP 405 Method Not Allowed for non-POST requests to the loopback MCP bridge.
private const val METHOD_NOT_ALLOWED_CODE = 405
