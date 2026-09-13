// NEW (v0.4.0, FEATURES.md §8): the entry point every client message goes through. HTTP-free by
// design — McpRoutes translates ktor calls into these methods — so the multiplexing contract (one
// process per spec, one MCP session per client session, ids remapped, notifications fanned out,
// idle reap, eviction at capacity) is tested without a socket.
//
// Lifecycle copies code mode's worker pool, the pool splice already runs: start on first use,
// reap after idleTimeout with no open notification stream and no request, evict the longest-idle
// streamless server when a spawn would exceed maxServers, and never replay a tool operation —
// a crash fails the pending calls in words and the next call respawns.
package splice.control.mcp

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.launch.McpSharing
import splice.core.util.LogSink
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val RPC_INVALID = -32600
private const val RPC_UNKNOWN_SESSION = -32001
private const val RPC_SERVER_ERROR = -32000
private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
private const val HTTP_UNAVAILABLE = 503
private const val SWEEP_PERIOD_S = 60L
private const val MILLIS_PER_MINUTE = 60_000L

/** What the transport writes back: status, an optional session header, and a JSON body (or none for 202). */
public data class McpReply(val status: Int, val body: String?, val sessionId: String? = null)

/** Reads the operator's global `mcpServers` object as it is on disk NOW; a seam for tests and for
 *  keeping ~/.claude.json parsing where it already lives (the app wires a tolerant read). */
public fun interface GlobalMcpServers {
    public operator fun invoke(): JsonObject
}

public class McpHost(
    sharing: McpSharing,
    global: GlobalMcpServers,
    private val config: McpHostConfig = McpHostConfig(),
    launcher: McpProcessLauncher = StdioProcessLauncher(),
    private val log: LogSink,
) {
    private val codec = JsonRpcCodec()
    private val sessions = McpSessions(config.clock)
    private val servers = HostedServers(sharing, global, config, launcher, codec, log, sessions)
    private val status = McpStatus(sharing, global, servers::get, sessions)

    @Volatile private var sweeper: ScheduledExecutorService? = null

    public fun start() {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mcp-host-sweep").apply { isDaemon = true }
        }
        exec.scheduleAtFixedRate({ runCatching { sweep() } }, SWEEP_PERIOD_S, SWEEP_PERIOD_S, TimeUnit.SECONDS)
        sweeper = exec
    }

    public fun stop() {
        sweeper?.shutdownNow()
        sweeper = null
        servers.closeAll("daemon stopping")
    }

    /** One client POST. [sessionId] is the `Mcp-Session-Id` header, absent on `initialize`;
     *  [protocolVersion] is the `MCP-Protocol-Version` header a client sends after initialize. */
    public suspend fun post(name: String, sessionId: String?, body: String, protocolVersion: String? = null): McpReply {
        val msg = codec.parse(body)
            ?: return bad(HTTP_BAD_REQUEST, JsonPrimitive(0), RPC_INVALID, "not a JSON-RPC object")
        val kind = codec.kind(msg)
        val id = msg["id"] ?: JsonPrimitive(0)
        return when {
            kind == RpcKind.INVALID -> bad(HTTP_BAD_REQUEST, id, RPC_INVALID, "invalid JSON-RPC")
            kind == RpcKind.REQUEST && codec.method(msg) == "initialize" -> initialize(name, msg)
            !protocolAccepted(name, sessionId, protocolVersion) ->
                bad(HTTP_BAD_REQUEST, id, RPC_INVALID, "unsupported MCP-Protocol-Version '$protocolVersion'")
            else -> forSession(name, sessionId, msg, kind)
        }
    }

    /** True when [protocolVersion] is absent or names the version the session negotiated. */
    public fun protocolAccepted(name: String, sessionId: String?, protocolVersion: String?): Boolean =
        protocolVersion == null || sessions.get(name, sessionId)?.protocolVersion == protocolVersion

    /** The session's notification stream for a GET; null when the session is unknown. */
    public fun openStream(name: String, sessionId: String?): ReceiveChannel<String>? {
        val session = sessions.get(name, sessionId) ?: return null
        session.openStreams.incrementAndGet()
        sessions.touch(session)
        return session.stream
    }

    public fun closeStream(name: String, sessionId: String?) {
        sessions.get(name, sessionId)?.let {
            sessions.touch(it)
            it.openStreams.decrementAndGet()
        }
    }

    /** DELETE: the client is done with this session. */
    public fun endSession(name: String, sessionId: String?): Boolean {
        val session = sessions.end(name, sessionId) ?: return false
        servers.get(name)?.failPending("session ended", session.id)
        return true
    }

    /** Idle reaping; public so tests drive it with a fake clock instead of waiting a minute. */
    public fun sweep() {
        val now = config.clock.millis()
        val limit = config.idleTimeout.inWholeMilliseconds
        servers.names()
            .filterNot { sessions.busy(it, now, limit) }
            .forEach { servers.close(it, "idle for ${limit / MILLIS_PER_MINUTE} min") }
    }

    /** `/api/mcp`. */
    public fun statusJson(): String = status.json()

    private suspend fun initialize(name: String, msg: JsonObject): McpReply {
        val id = msg.getValue("id")
        val result = try {
            servers.acquire(name).ensureStarted()
        } catch (e: McpHostException) {
            return bad(HTTP_UNAVAILABLE, id, RPC_SERVER_ERROR, e.message.orEmpty())
        }
        val session = sessions.create(name)
        // The child's answer, verbatim: the server picks the protocol version (MCP: a client that
        // cannot speak it disconnects), and inventing the client's requested one would promise a
        // dialect the child never negotiated.
        session.protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.content
        return McpReply(HTTP_OK, codec.encode(codec.result(id, result)), session.id)
    }

    private suspend fun forSession(name: String, sessionId: String?, msg: JsonObject, kind: RpcKind): McpReply {
        val id = msg["id"] ?: JsonPrimitive(0)
        val session = sessions.get(name, sessionId)
            ?: return bad(HTTP_NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
        sessions.touch(session)
        val server = servers.get(name) ?: return bad(HTTP_NOT_FOUND, id, RPC_UNKNOWN_SESSION, "server not hosted")
        return when (kind) {
            RpcKind.REQUEST -> forward(server, session, msg)
            RpcKind.NOTIFICATION -> notification(name, server, session, msg)
            RpcKind.RESPONSE, RpcKind.INVALID -> McpReply(HTTP_ACCEPTED, null)
        }
    }

    /** 202 when the child took it; 503 in words when it could not — never a silent drop. */
    private suspend fun notification(
        name: String,
        server: HostedServer,
        session: McpSession,
        msg: JsonObject,
    ): McpReply {
        val delivered = try {
            server.notify(session.id, msg)
        } catch (e: McpHostException) {
            log("[mcp-host] $name: notify failed (${e.message})\n")
            false
        }
        return if (delivered) {
            McpReply(HTTP_ACCEPTED, null)
        } else {
            bad(HTTP_UNAVAILABLE, JsonPrimitive(0), RPC_SERVER_ERROR, "hosted MCP server '$name' is not running")
        }
    }

    private suspend fun forward(server: HostedServer, session: McpSession, msg: JsonObject): McpReply {
        val id = msg.getValue("id")
        val answer = try {
            server.call(session.id, id, msg)
        } catch (e: McpHostException) {
            codec.error(id, RPC_SERVER_ERROR, e.message.orEmpty())
        }
        sessions.touch(session)
        return McpReply(HTTP_OK, codec.encode(answer), session.id)
    }

    private fun bad(status: Int, id: JsonElement, code: Int, message: String) =
        McpReply(status, codec.encode(codec.error(id, code, message)))
}
