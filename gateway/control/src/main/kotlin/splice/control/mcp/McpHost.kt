// NEW: v0.4.0 FEATURES.md §8 — the entry point every client message goes through. HTTP-free by
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
    private val status = McpStatus(sharing, global, HostedServerLookup(servers::get), sessions)

    @Volatile private var sweeper: ScheduledExecutorService? = null

    public fun start() {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Executors.defaultThreadFactory().newThread(r).apply {
                name = "mcp-host-sweep"
                isDaemon = true
            }
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

    /** True when [protocolVersion] names the version the session negotiated, or is absent. Absence is
     *  deliberate, not lax: MCP's Streamable HTTP transport says a server that gets no
     *  MCP-Protocol-Version header falls back to what it can identify otherwise — "for example, by
     *  relying on the protocol version negotiated during initialization" — and this host has exactly
     *  that per session. A header that names ANY other version is a 400 — but only on a session that
     *  EXISTS: an ended or unknown session is the session check's 404 (review 3 addendum), so a
     *  client that kept the old negotiated version after DELETE is told to reinitialize, not that
     *  its version is wrong. */
    public fun protocolAccepted(name: String, sessionId: String?, protocolVersion: String?): Boolean {
        val session = sessions.get(name, sessionId) ?: return true
        return protocolVersion == null || session.protocolVersion == protocolVersion
    }

    /** The session's notification stream for a GET; null when the session is unknown. */
    public fun openStream(name: String, sessionId: String?): ReceiveChannel<String>? {
        val server = servers.reserve(name) ?: return null
        return try {
            sessions.get(name, sessionId)?.let { session ->
                session.openStreams.incrementAndGet()
                sessions.touch(session)
                session.stream
            }
        } finally {
            servers.release(name, server)
        }
    }

    public fun closeStream(name: String, sessionId: String?) {
        val server = servers.reserve(name) ?: return
        try {
            sessions.closeStream(name, sessionId)
        } finally {
            servers.release(name, server)
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
        // The registry checks liveness and unbinds under the same lock used to acquire request leases.
        servers.sweep(now, limit)
    }

    /** `/api/mcp`. */
    public fun statusJson(): String = status.json()

    private suspend fun initialize(name: String, msg: JsonObject): McpReply {
        val id = msg.getValue("id")
        // acquire() reserves the server against eviction until release(); the session is created
        // inside that window and kept only if the server is still bound and alive when it ends.
        val server = try {
            servers.acquire(name)
        } catch (e: McpHostException) {
            return bad(HTTP_UNAVAILABLE, id, RPC_SERVER_ERROR, e.message.orEmpty())
        }
        var session: McpSession? = null
        var failure = ""
        // The reservation ends in a finally: a client that cancels mid-handshake (review 4) must not
        // leave the server marked "starting" forever, or capacity would refuse every newcomer.
        try {
            session = sessions.create(name, server.ensureStarted())
        } catch (e: McpHostException) {
            failure = e.message.orEmpty()
        } finally {
            if (!servers.release(name, server)) {
                session?.let { sessions.end(name, it.id) }
                failure = failure.ifEmpty { "hosted MCP server '$name' was replaced while starting" }
            }
        }
        val minted = session?.takeIf { failure.isEmpty() }
        return minted?.let { McpReply(HTTP_OK, codec.encode(codec.result(id, it.initResult)), it.id) }
            ?: bad(HTTP_UNAVAILABLE, id, RPC_SERVER_ERROR, failure)
    }

    private suspend fun forSession(name: String, sessionId: String?, msg: JsonObject, kind: RpcKind): McpReply {
        val id = msg["id"] ?: JsonPrimitive(0)
        val session = sessions.get(name, sessionId)
            ?: return bad(HTTP_NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
        sessions.touch(session)
        val server = servers.reserve(name) ?: return bad(HTTP_NOT_FOUND, id, RPC_UNKNOWN_SESSION, "server not hosted")
        return try {
            if (sessions.get(name, sessionId) !== session) {
                bad(HTTP_NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
            } else {
                when (kind) {
                    RpcKind.REQUEST -> forward(server, session, msg)
                    RpcKind.NOTIFICATION -> notification(name, server, session, msg)
                    RpcKind.RESPONSE, RpcKind.INVALID -> McpReply(HTTP_ACCEPTED, null)
                }
            }
        } finally {
            sessions.touch(session)
            servers.release(name, server)
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
