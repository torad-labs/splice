// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md). Loopback HTTP MCP bridge for Claude Desktop's
// in-app (`type: "sdk"` / `sdkMcpServers`) MCP servers: hands agy one authenticated loopback URL per server and
// relays every JSON-RPC POST to Desktop as a `control_request` (`subtype: "mcp_message"`).
package splice.provider.antigravity

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Callback when a bridged SDK MCP server completes its MCP `initialize` handshake with Desktop. */
internal fun interface BridgeConnected {
    fun connected(serverName: String)
}

/** Loopback HTTP bridge serving Desktop's in-app SDK MCP servers to agy. */
internal class McpBridge(
    private val out: DesktopOut,
    private val onConnected: BridgeConnected,
) : AutoCloseable {
    private val tokenBytes: ByteArray = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
    private val tokenHex: String = tokenBytes.joinToString("") { "%02x".format(it) }
    private val expectedAuth: ByteArray = "Bearer $tokenHex".toByteArray(StandardCharsets.UTF_8)
    private val servers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending = ConcurrentHashMap<String, PendingRpc>()
    private val ignoredReplies: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicLong(0)
    private val executor = Executors.newCachedThreadPool { run ->
        Executors.defaultThreadFactory().newThread(run).apply {
            name = "splice-mcp-bridge"
            isDaemon = true
        }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(MCP_PREFIX, ::handle)
        executor = this@McpBridge.executor
        start()
    }

    val port: Int get() = server.address.port

    fun register(names: Collection<String>) {
        servers.addAll(names)
    }

    fun urlFor(name: String): String = "http://127.0.0.1:$port$MCP_PREFIX$name"

    fun authHeader(): String = "Bearer $tokenHex"

    /** Routes Desktop's `control_response` to a waiting HTTP request if it belongs to this bridge; returns true if
     *  consumed so permission prompts ignore it. */
    fun answered(response: JsonObject?): Boolean {
        val id = JsonScalars.str(response, "request_id") ?: return false
        val rpc = pending.remove(id)
        if (rpc != null) rpc.future.complete(response ?: JsonObject(emptyMap()))
        return rpc != null || ignoredReplies.remove(id)
    }

    override fun close() {
        pending.values.forEach { it.future.cancel(true) }
        pending.clear()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            dispatch(exchange)
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange) {
        val serverName = validateRequest(exchange) ?: return
        val rawBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val msg = JsonScalars.objectOrNull(Json, rawBody)
        if (msg == null) {
            send(exchange, HttpStatus.BAD_REQUEST, "")
        } else {
            relay(exchange, serverName, msg)
        }
    }

    private fun validateRequest(exchange: HttpExchange): String? {
        val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(StandardCharsets.UTF_8)
        val name = exchange.requestURI.path.removePrefix(MCP_PREFIX)
        val errorStatus = when {
            !exchange.requestMethod.equals("POST", ignoreCase = true) -> METHOD_NOT_ALLOWED
            !MessageDigest.isEqual(auth, expectedAuth) -> HttpStatus.UNAUTHORIZED
            !isKnownServer(name) -> HttpStatus.NOT_FOUND
            else -> null
        }
        if (errorStatus != null) send(exchange, errorStatus, "")
        return if (errorStatus == null) name else null
    }

    private fun isKnownServer(name: String): Boolean = name.isNotEmpty() && '/' !in name && name in servers

    private fun relay(exchange: HttpExchange, serverName: String, msg: JsonObject) {
        val requestId = "mcp-${ids.incrementAndGet()}"
        val rpcId = msg["id"]
        val isNotification = rpcId == null || rpcId is JsonNull
        if (isNotification) {
            ignoredReplies.add(requestId)
            out(mcpControlRequest(requestId, serverName, msg))
            send(exchange, HttpStatus.ACCEPTED, "")
            return
        }
        val isInit = JsonScalars.str(msg, "method") == "initialize"
        val rpc = PendingRpc(rpcId, CompletableFuture())
        pending[requestId] = rpc
        out(mcpControlRequest(requestId, serverName, msg))
        val reply = awaitReply(requestId, rpc)
        val subtype = JsonScalars.str(reply, "subtype")
        if (subtype == "success") {
            val mcpResponse = (reply?.get("response") as? JsonObject)?.get("mcp_response") as? JsonObject
            if (mcpResponse != null) {
                if (isInit) onConnected.connected(serverName)
                send(exchange, HttpStatus.OK, JsonWire.string(mcpResponse))
                return
            }
        }
        val errText = JsonScalars.str(reply, "error") ?: "MCP bridge request failed"
        send(exchange, HttpStatus.OK, JsonWire.string(jsonRpcError(rpcId, errText)))
    }

    private fun awaitReply(requestId: String, rpc: PendingRpc): JsonObject? = try {
        rpc.future.get(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        pending.remove(requestId)
        null
    } catch (_: ExecutionException) {
        pending.remove(requestId)
        null
    } catch (_: TimeoutException) {
        pending.remove(requestId)
        null
    } catch (_: CancellationException) {
        pending.remove(requestId)
        null
    }

    private fun mcpControlRequest(requestId: String, serverName: String, message: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            putJsonObject("request") {
                put("subtype", "mcp_message")
                put("server_name", serverName)
                put("message", message)
            }
        }

    private fun jsonRpcError(rpcId: JsonElement, message: String): JsonObject {
        val details = buildJsonObject {
            put("code", RPC_SERVER_ERROR)
            put("message", message)
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put(RPC_ERROR_FIELD, details)
        }
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isNotEmpty()) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } else {
            exchange.sendResponseHeaders(status, -1)
        }
    }

    private data class PendingRpc(val rpcId: JsonElement, val future: CompletableFuture<JsonObject>)
}

private const val MCP_PREFIX = "/mcp/"
private const val TOKEN_BYTES = 32
private const val BRIDGE_TIMEOUT_SECONDS = 60L
private const val RPC_SERVER_ERROR = -32000
private const val RPC_ERROR_FIELD = "error"
private const val METHOD_NOT_ALLOWED = 405
