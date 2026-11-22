// NEW: desktop-antigravity (door chat). Loopback HTTP MCP server exposing a Claude Desktop Chat
// turn's Anthropic `tools` to a pooled `agy acp` session. When agy invokes a bridged tool via
// `tools/call`, the HTTP handler suspends on a CompletableFuture and emits the call to the active
// HTTP turn so Splice ends the turn with `tool_use`. When Desktop Chat follows up with
// `tool_result`, `supplyResults` completes the future and agy seamlessly continues.
package splice.provider.antigravity.door

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import splice.provider.antigravity.MCP_WIRE_PREFIX
import splice.provider.antigravity.McpBridgeWire
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** One Anthropic custom tool definition exposed to agy as an MCP tool. */
internal data class AnthropicToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** One tool call agy invoked on our loopback MCP server, paused waiting for Desktop Chat's result. */
internal data class BridgedToolCall(
    val id: String,
    val name: String,
    val inputJson: String,
)

/** One tool result Desktop Chat returned in its follow-up `/v1/messages` request. */
internal data class BridgedToolResult(
    val toolUseId: String,
    val content: String,
    val isError: Boolean,
)

/** Listener notified whenever agy invokes a bridged tool over the loopback MCP server. */
internal fun interface ToolCallListener {
    operator fun invoke(call: BridgedToolCall)
}

/** Virtual loopback HTTP MCP server for one pooled Desktop Chat `agy acp` session. */
internal class DoorToolBridge : AutoCloseable {
    private val tokenHex: String = ByteArray(DOOR_BRIDGE_TOKEN_BYTES)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val wire = McpBridgeWire("Bearer $tokenHex".toByteArray(StandardCharsets.UTF_8), setOf(CHAT_MCP_SERVER))
    private val toolsRef = AtomicReference<List<AnthropicToolDef>>(emptyList())
    private val onCallRef = AtomicReference<ToolCallListener?>(null)
    private val pending = ConcurrentHashMap<String, CompletableFuture<BridgedToolResult>>()
    private val executor = Executors.newCachedThreadPool { task ->
        Executors.defaultThreadFactory().newThread(task).apply {
            name = "splice-agy-door-mcp"
            isDaemon = true
        }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(MCP_WIRE_PREFIX, ::handle)
        executor = this@DoorToolBridge.executor
        start()
    }

    val port: Int get() = server.address.port

    fun setTools(tools: List<AnthropicToolDef>) {
        toolsRef.set(tools)
    }

    fun beginTurn(onCall: ToolCallListener) {
        onCallRef.set(onCall)
    }

    fun endTurn() {
        onCallRef.set(null)
    }

    fun matchesAny(results: List<BridgedToolResult>): Boolean =
        results.any { pending.containsKey(it.toolUseId) }

    fun supplyResults(results: List<BridgedToolResult>): Int {
        var matched = 0
        for (result in results) {
            val future = pending.remove(result.toolUseId) ?: continue
            if (future.complete(result)) matched += 1
        }
        return matched
    }

    fun cancelPending(reason: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.complete(BridgedToolResult(id, reason, isError = true))
        }
    }

    /** ACP `mcpServers` entry pointing agy at this loopback virtual server. */
    fun acpServerSpec(): JsonObject = buildJsonObject {
        put("name", CHAT_MCP_SERVER)
        put("type", "http")
        put("url", "http://127.0.0.1:$port$MCP_WIRE_PREFIX$CHAT_MCP_SERVER")
        putJsonArray("headers") {
            add(
                buildJsonObject {
                    put("name", "Authorization")
                    put("value", "Bearer $tokenHex")
                },
            )
        }
    }

    override fun close() {
        endTurn()
        cancelPending("session closed")
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (wire.validate(exchange) == null) return
            val rawBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val msg = JsonScalars.objectOrNull(Json, rawBody)
            if (msg == null) {
                wire.send(exchange, HttpStatus.BAD_REQUEST, "")
            } else {
                dispatch(exchange, msg)
            }
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange, msg: JsonObject) {
        val rpcId = msg["id"]
        val method = JsonScalars.str(msg, "method").orEmpty()
        if (rpcId == null || rpcId is JsonNull) {
            wire.send(exchange, HttpStatus.ACCEPTED, "")
            return
        }
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        val response = when (method) {
            "initialize" -> DoorToolRpc.result(rpcId, DoorToolRpc.initialize(params))
            "ping" -> DoorToolRpc.result(rpcId, JsonObject(emptyMap()))
            "tools/list" -> DoorToolRpc.result(rpcId, DoorToolRpc.toolsList(toolsRef.get()))
            "tools/call" -> handleToolCall(rpcId, params)
            else -> DoorToolRpc.result(rpcId, JsonObject(emptyMap()))
        }
        wire.send(exchange, HttpStatus.OK, JsonWire.string(response))
    }

    private fun handleToolCall(rpcId: JsonElement, params: JsonObject): JsonObject {
        val name = JsonScalars.str(params, "name").orEmpty()
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val listener = onCallRef.get()
            ?: return DoorToolRpc.result(
                rpcId,
                DoorToolRpc.toolContent("No active chat turn to execute tool '$name'", isError = true),
            )
        val id = "toolu_${UUID.randomUUID().toString().replace("-", "").take(TOOL_ID_CHARS)}"
        val future = CompletableFuture<BridgedToolResult>()
        pending[id] = future
        listener(BridgedToolCall(id, name, JsonWire.string(args)))
        val result = awaitResult(id, future)
        return DoorToolRpc.result(rpcId, DoorToolRpc.toolContent(result.content, result.isError))
    }

    private fun awaitResult(id: String, future: CompletableFuture<BridgedToolResult>): BridgedToolResult = try {
        future.get(TOOL_WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        pending.remove(id)
        BridgedToolResult(id, "tool execution interrupted", isError = true)
    } catch (_: TimeoutException) {
        pending.remove(id)
        BridgedToolResult(id, "timed out waiting for client tool result", isError = true)
    } catch (_: ExecutionException) {
        pending.remove(id)
        BridgedToolResult(id, "tool execution failed", isError = true)
    }
}

internal const val CHAT_MCP_SERVER: String = "chat"
internal const val CHAT_MCP_TOOL_PREFIX: String = "mcp_chat_"

// why: 256-bit random bearer token per loopback MCP bridge instance prevents local cross-process probing.
private const val DOOR_BRIDGE_TOKEN_BYTES = 32

// why: matches Anthropic's 24-hex-character `toolu_` identifier length expected by Desktop Chat.
private const val TOOL_ID_CHARS = 24

// why: bounds how long a paused loopback MCP tool call waits for Desktop Chat to return a tool_result.
private const val TOOL_WAIT_SECONDS = 300L
