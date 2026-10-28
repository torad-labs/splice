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
import splice.client.mcp.McpInventory
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpSharing
import splice.control.mcp.hub.McpHubCalls
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val RPC_INVALID = -32600
private const val RPC_UNKNOWN_SESSION = -32001
internal const val RPC_SERVER_ERROR = -32000

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

    /** Source-aware entries keep a durable registered service distinct from an ordinary client declaration. */
    public fun inputs(): List<McpServerInput> = invoke().map { (name, entry) -> McpServerInput(name, entry) }
}

public class McpHost(
    /** How servers are classed for the hub (MCP hub step 3); the plans in /api/mcp class every source with it. */
    public val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val config: McpHostConfig = McpHostConfig(),
    launcher: McpProcessLauncher? = null,
    private val log: LogSink,
    /** V4-146: the five-kind census; null keeps today's `/api/mcp` shape exactly (every existing
     *  caller that builds an McpHost without one — every test fixture included). */
    inventory: McpInventory? = null,
    /** MCP hub steps 3 and 4: the daemon half the hub front calls (resolve, start, listing); null when the daemon
     *  serves no front. Its per-session holders are stopped by this host's sweep once their session ends. */
    public val hub: McpHubCalls? = null,
) {
    private val codec = JsonRpcCodec()

    // V4-147: the default launcher is built HERE because containment reports in words, and the log
    // sink is a constructor argument — a default argument could not have named it.
    private val spawner = launcher ?: StdioProcessLauncher(containment = McpContainment(log, config.slice))
    private val sessions = McpSessions(config.clock)
    private val servers = HostedServers(sharing, global, config, spawner, codec, log, sessions)
    private val minting = SessionMinting(servers, sessions, log)
    private val posts = McpPostHandler(codec, sharing, global, servers, sessions, minting, log)
    private val status = McpStatus(sharing, global, HostedServerLookup(servers::get), sessions, inventory)

    /** `POST /api/mcp/{name}/test`: starts a server when it is not running, lists its tools, and says what came back. */
    public val probe: McpProbe =
        McpProbe(this, status, HostedServerLookup(servers::get), config.clock, config.testListTimeout)

    /** Management-only pause, settlement status and compatible replacement for a hosted server. */
    public val replacements: McpReplacements = McpReplacements(servers.replacements)

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
    public suspend fun post(
        name: String,
        sessionId: String?,
        body: String,
        protocolVersion: String? = null,
        head: String? = null,
    ): McpReply = posts.post(name, sessionId, body, protocolVersion, head)

    /** GET and DELETE validate the same server-negotiated protocol as POST, including adopted sessions. */
    public fun protocolAccepted(name: String, sessionId: String?, protocolVersion: String?): Boolean =
        posts.protocolAccepted(name, sessionId, protocolVersion)

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
        // ONE reading for this sweep, so every server in it is judged against the same window.
        val limit = config.idleTimeout().inWholeMilliseconds
        // The registry checks liveness and unbinds under the same lock used to acquire request leases.
        servers.sweep(now, limit)
        hub?.sweep(now, limit)
    }

    /** `/api/mcp`. */
    public fun statusJson(): String = status.json()

    /** Exact cached listings for this head's currently shared declarations; per-session servers never enter it. */
    public fun sharedTools(headKey: String): Map<String, JsonObject> =
        servers.catalogs.read(sharing.plan(global.inputs(), headKey).hosted)
}

/** Keeps admission, initialization, and session routing below McpHost's lifecycle surface. */
private class McpPostHandler(
    private val codec: JsonRpcCodec,
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val servers: HostedServers,
    private val sessions: McpSessions,
    private val minting: SessionMinting,
    private val log: LogSink,
) {
    suspend fun post(
        name: String,
        sessionId: String?,
        body: String,
        protocolVersion: String?,
        head: String?,
    ): McpReply {
        val msg = codec.parse(body)
            ?: return bad(HttpStatus.BAD_REQUEST, JsonPrimitive(0), RPC_INVALID, "not a JSON-RPC object")
        val message = McpHostMessage(msg, codec)
        if (message.kind == RpcKind.INVALID) {
            return bad(HttpStatus.BAD_REQUEST, message.id, RPC_INVALID, "invalid JSON-RPC")
        }
        val admission = admission(name, sessionId, message)
        return route(name, McpClient(sessionId, protocolVersion, head), msg, message, admission)
    }

    private suspend fun admission(name: String, sessionId: String?, message: McpHostMessage): McpAdmission? =
        if (message.settlement) null else servers.replacements.admit(name, sessions.get(name, sessionId))

    private suspend fun route(
        name: String,
        client: McpClient,
        msg: JsonObject,
        message: McpHostMessage,
        admission: McpAdmission?,
    ): McpReply {
        if (!accepts(name, msg, message, admission)) {
            return bad(
                HttpStatus.SERVICE_UNAVAILABLE,
                message.id,
                RPC_SERVER_ERROR,
                "no hosted MCP admission for '$name'",
            )
        }
        return try {
            when {
                initializes(message.kind, msg) -> initialize(name, msg, client.head, admission)
                !protocolAccepted(name, client.sessionId, client.protocolVersion) -> bad(
                    HttpStatus.BAD_REQUEST,
                    message.id,
                    RPC_INVALID,
                    "unsupported MCP-Protocol-Version '${client.protocolVersion}'",
                )
                else -> forSession(name, client, msg, message.kind, admission)
            }
        } finally {
            admission?.leave()
        }
    }

    private fun accepts(name: String, msg: JsonObject, message: McpHostMessage, admission: McpAdmission?): Boolean {
        val unknownInitialize = initializes(message.kind, msg) && sharing.hostedSpec(global.inputs(), name) == null
        return message.accepts(admission) || unknownInitialize
    }

    private fun initializes(kind: RpcKind, msg: JsonObject): Boolean =
        kind == RpcKind.REQUEST && codec.method(msg) == "initialize"

    /** An unknown session must reach the session path's 404 before protocol validation. */
    fun protocolAccepted(name: String, sessionId: String?, protocolVersion: String?): Boolean {
        val session = sessions.get(name, sessionId) ?: return true
        return session.adopted || protocolVersion == null || session.protocolVersion == protocolVersion
    }

    private suspend fun initialize(name: String, msg: JsonObject, head: String?, admission: McpAdmission?): McpReply {
        val id = msg.getValue("id")
        val minted = minting.mint(name, null, head, admission)
        val code = if (minted.timedOut) RPC_TIMED_OUT else RPC_SERVER_ERROR
        return minted.session?.let { McpReply(HTTP_OK, codec.encode(codec.result(id, it.initResult)), it.id) }
            ?: bad(HttpStatus.SERVICE_UNAVAILABLE, id, code, minted.failure)
    }

    private suspend fun forSession(
        name: String,
        client: McpClient,
        msg: JsonObject,
        kind: RpcKind,
        admission: McpAdmission?,
    ): McpReply {
        val id = msg["id"] ?: JsonPrimitive(0)
        val sessionId = client.sessionId
        val session = sessions.get(name, sessionId)
            ?: minting.adopt(name, client, kind, admission)
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
        val delivered = when (val sent = server.notify(session.id, msg)) {
            is McpResult.Served -> sent.value
            is McpResult.Refused -> {
                log("[mcp-host] ${LogSafe.str(name)}: notify failed (${LogSafe.str(sent.reason)})\n")
                false
            }
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
        val answer = server.call(session.id, id, msg)
        sessions.touch(session)
        return McpReply(HTTP_OK, codec.encode(answer), session.id)
    }

    private fun bad(status: Int, id: JsonElement, code: Int, message: String) =
        McpReply(status, codec.encode(codec.error(id, code, message)))
}
