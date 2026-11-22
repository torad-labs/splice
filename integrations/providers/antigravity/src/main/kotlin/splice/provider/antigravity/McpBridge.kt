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
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
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
internal interface BridgeConnected {
    fun connected(serverName: String)
}

/** Loopback HTTP bridge serving Desktop's in-app SDK MCP servers to agy. */
internal class McpBridge(
    private val out: DesktopOut,
    private val onConnected: BridgeConnected,
) : AutoCloseable {
    private val tokenHex: String = ByteArray(BRIDGE_TOKEN_BYTES)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val servers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val wire = McpBridgeWire("Bearer $tokenHex".toByteArray(StandardCharsets.UTF_8), servers)
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
        createContext(MCP_WIRE_PREFIX, ::handle)
        executor = this@McpBridge.executor
        start()
    }

    val port: Int get() = server.address.port

    fun register(names: Collection<String>) {
        servers.addAll(names)
    }

    fun urlFor(name: String): String = "http://127.0.0.1:$port$MCP_WIRE_PREFIX$name"

    fun authHeader(): String = "Bearer $tokenHex"

    /** Routes Desktop's `control_response` to a waiting HTTP request if it belongs to this bridge. */
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
            val serverName = wire.validate(exchange) ?: return
            val rawBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val msg = JsonScalars.objectOrNull(Json, rawBody)
            if (msg == null) wire.send(exchange, HttpStatus.BAD_REQUEST, "") else relay(exchange, serverName, msg)
        } finally {
            exchange.close()
        }
    }

    private fun relay(exchange: HttpExchange, serverName: String, msg: JsonObject) {
        val requestId = "mcp-${ids.incrementAndGet()}"
        val rpcId = msg["id"]
        if (rpcId == null || rpcId is JsonNull) {
            ignoredReplies.add(requestId)
            out(wire.controlRequest(requestId, serverName, msg))
            wire.send(exchange, HttpStatus.ACCEPTED, "")
            return
        }
        val isInit = JsonScalars.str(msg, "method") == "initialize"
        val rpc = PendingRpc(rpcId, CompletableFuture())
        pending[requestId] = rpc
        out(wire.controlRequest(requestId, serverName, msg))
        val reply = awaitReply(requestId, rpc)
        val mcpResponse = reply?.takeIf { JsonScalars.str(it, "subtype") == "success" }
            ?.let { (it["response"] as? JsonObject)?.get("mcp_response") as? JsonObject }
        if (mcpResponse != null) {
            if (isInit) onConnected.connected(serverName)
            wire.send(exchange, HttpStatus.OK, JsonWire.string(mcpResponse))
        } else {
            val errText = JsonScalars.str(reply, "error") ?: "MCP bridge request failed"
            wire.send(exchange, HttpStatus.OK, JsonWire.string(wire.rpcError(rpcId, errText)))
        }
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

    private data class PendingRpc(val rpcId: JsonElement, val future: CompletableFuture<JsonObject>)
}

// why: 32 random bytes encode to a 64-hex-character bearer token for the loopback MCP bridge.
private const val BRIDGE_TOKEN_BYTES = 32

// why: Desktop answers mcp_message requests in milliseconds; 60 seconds bounds a stalled client.
private const val BRIDGE_TIMEOUT_SECONDS = 60L
