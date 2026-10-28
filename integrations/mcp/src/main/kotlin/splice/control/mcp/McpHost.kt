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
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpInventory
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpSharing
import splice.control.mcp.catalog.McpSearchCatalogs
import splice.control.mcp.hosted.McpPostHandler
import splice.control.mcp.hosted.McpSweeper
import splice.control.mcp.hub.McpHubCalls
import splice.core.util.LogSink

internal const val RPC_SERVER_ERROR = -32000

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

    /** Shared catalog observations have their own current-configuration and source-identity boundary. */
    public val catalogs: McpSearchCatalogs = McpSearchCatalogs(sharing, global, servers.catalogs, hub)

    private val sweeper = McpSweeper { sweep() }

    public fun start() {
        sweeper.start()
    }

    public fun stop() {
        sweeper.stop()
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
}
