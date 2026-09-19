// NEW: v0.4.0 FEATURES.md §8 — the entry point every client message goes through. HTTP-free by
// design — McpRoutes translates ktor calls into these methods — so the multiplexing contract (one
// process per spec, one MCP session per client session, ids remapped, notifications fanned out,
// idle reap, eviction at capacity) is tested without a socket.
//
// Lifecycle copies code mode's worker pool, the pool splice already runs: start on first use,
// reap after idleTimeout with no open notification stream and no request, evict the longest-idle
// streamless server when a spawn would exceed maxServers, and never replay a tool operation —
// a crash fails the pending calls in words and the next call respawns.
//
// V4-148 (2026-09-19): a session id this host does not know is ADOPTED, not answered with the spec's
// "session not found". That signal means reinitialize, and Claude Code does not act on it: its tools
// stop working for the rest of the session while /mcp still shows the server CONNECTED, /mcp reconnect
// does not recover it, and only a full relaunch does (claude-code #60949, #55970, #59442, #55228).
// Splice writes the value that triggers it, so the fix is ours: a restart, an idle reap or an eviction
// is invisible to the client, which keeps the id it already holds.
package splice.control.mcp

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.launch.McpSharing
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val RPC_INVALID = -32600
private const val RPC_UNKNOWN_SESSION = -32001
private const val RPC_SERVER_ERROR = -32000

// HTTP_OK and HTTP_ACCEPTED stay local: HttpStatus declares no 2xx constant, and the wall is silent
// on them. The three ERROR statuses read HttpStatus now.
private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
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
    launcher: McpProcessLauncher? = null,
    private val log: LogSink,
) {
    private val codec = JsonRpcCodec()

    // V4-147: the default launcher is built HERE because containment reports in words, and the log
    // sink is a constructor argument — a default argument could not have named it.
    private val spawner = launcher ?: StdioProcessLauncher(containment = McpContainment(log))
    private val sessions = McpSessions(config.clock)
    private val servers = HostedServers(sharing, global, config, spawner, codec, log, sessions)
    private val minting = SessionMinting(servers, sessions, log)
    private val status = McpStatus(sharing, global, HostedServerLookup(servers::get), sessions)

    @Volatile private var sweeper: ScheduledExecutorService? = null

    public fun start() {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Executors.defaultThreadFactory().newThread(r).apply {
                name = "mcp-host-sweep"
                isDaemon = true
            }
        }
        exec.scheduleAtFixedRate(
            { Cancellables.runCatchingCancellable { sweep() } },
            SWEEP_PERIOD_S,
            SWEEP_PERIOD_S,
            TimeUnit.SECONDS,
        )
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
            ?: return bad(HttpStatus.BAD_REQUEST, JsonPrimitive(0), RPC_INVALID, "not a JSON-RPC object")
        val kind = codec.kind(msg)
        val id = msg["id"] ?: JsonPrimitive(0)
        return when {
            kind == RpcKind.INVALID -> bad(HttpStatus.BAD_REQUEST, id, RPC_INVALID, "invalid JSON-RPC")
            kind == RpcKind.REQUEST && codec.method(msg) == "initialize" -> initialize(name, msg)
            !protocolAccepted(name, sessionId, protocolVersion) ->
                bad(HttpStatus.BAD_REQUEST, id, RPC_INVALID, "unsupported MCP-Protocol-Version '$protocolVersion'")
            else -> forSession(name, sessionId, msg, kind, protocolVersion)
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
        // V4-148: an ADOPTED session handshook with the child AFTER the client last spoke, so the
        // version the client names is the one it negotiated with the child that ran before. Refusing
        // it would trade this row's 404 for a 400 and leave the client just as broken; the difference
        // between the two versions is logged where the session is adopted, once, instead.
        return session.adopted || protocolVersion == null || session.protocolVersion == protocolVersion
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
        val minted = minting.mint(name, null)
        return minted.session?.let { McpReply(HTTP_OK, codec.encode(codec.result(id, it.initResult)), it.id) }
            ?: bad(HttpStatus.SERVICE_UNAVAILABLE, id, RPC_SERVER_ERROR, minted.failure)
    }

    private suspend fun forSession(
        name: String,
        sessionId: String?,
        msg: JsonObject,
        kind: RpcKind,
        protocolVersion: String? = null,
    ): McpReply {
        val id = msg["id"] ?: JsonPrimitive(0)
        val session = sessions.get(name, sessionId)
            ?: minting.adopt(name, sessionId, kind, protocolVersion)
            ?: return bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
        sessions.touch(session)
        val server = servers.reserve(name)
            ?: return bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "server not hosted")
        return try {
            if (sessions.get(name, sessionId) !== session) {
                bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
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
            bad(
                HttpStatus.SERVICE_UNAVAILABLE,
                JsonPrimitive(0),
                RPC_SERVER_ERROR,
                "hosted MCP server '$name' is not running",
            )
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
