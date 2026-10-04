// NEW: V4-148 (2026-09-19) — where a client session is minted on a hosted child, and where an id the
// client ALREADY HOLDS is adopted instead of refused.
//
// Split from McpHost because it is one policy with two entry points (a client's own initialize, and a
// request whose session this host does not know) and both need the same reservation dance around the
// child's handshake.
package splice.control.mcp

import splice.core.util.LogSink

// why: how much of a session id a log line carries — enough to follow one session across lines, never
// the whole id, which adoption makes a hint a caller may hold rather than a capability.
private const val SESSION_TAG = 8

/** A minted session, or why it could not be: both entry points answer the client with the words. */
internal data class Minted(val session: McpSession?, val failure: String)

internal class SessionMinting(
    private val servers: HostedServers,
    private val sessions: McpSessions,
    private val log: LogSink,
) {

    /** A session on [name]'s child: [sessionId] null mints a fresh id, non-null adopts the client's own. */
    suspend fun mint(name: String, sessionId: String?): Minted {
        // acquire() reserves the server against eviction until release(); the handshake happens inside
        // that window and the session is registered only if the server is still bound when it ends —
        // so no session is ever created that must then be un-created.
        val server = try {
            servers.acquire(name)
        } catch (e: McpHostException) {
            return Minted(null, e.message.orEmpty())
        }
        var initResult: kotlinx.serialization.json.JsonObject? = null
        var failure = ""
        // The reservation ends in a finally: a client that cancels mid-handshake (review 4) must not
        // leave the server marked "starting" forever, or capacity would refuse every newcomer.
        try {
            initResult = server.ensureStarted()
        } catch (e: McpHostException) {
            failure = e.message.orEmpty()
        } finally {
            if (!servers.release(name, server)) {
                failure = failure.ifEmpty { "hosted MCP server '$name' was replaced while starting" }
            }
        }
        val session = initResult?.takeIf { failure.isEmpty() }?.let { sessions.create(name, it, sessionId) }
        return session?.let { Minted(it, "") } ?: Minted(null, failure)
    }

    /** The session for an id this host does not know — minted under the CLIENT's id, so a restart, an
     *  idle reap or an eviction never reaches the client as the reinitialize signal it ignores. Null
     *  leaves the caller's 404 in place.
     *
     *  What adoption costs, stated rather than assumed: a session id stops being unguessable, because
     *  the host can no longer tell one it issued from one a caller invented. That is acceptable ONLY
     *  because /mcp is bearer-guarded (ControlServer wires mcpAccessKey from the management key), so
     *  reaching this code already takes that key — the id is a hint here, never a capability.
     *
     *  Refused for anything but a REQUEST (a notification needs no session to be answered), and for an
     *  id the host still holds or the client ended ([McpSessions.adoptable]). */
    suspend fun adopt(name: String, sessionId: String?, kind: RpcKind, protocolVersion: String?): McpSession? {
        if (kind != RpcKind.REQUEST || !sessions.adoptable(sessionId)) return null
        val minted = mint(name, sessionId)
        minted.session?.let { session -> log(adopted(name, session, protocolVersion)) }
        return minted.session
    }

    private fun adopted(name: String, session: McpSession, protocolVersion: String?): String {
        val versions = if (protocolVersion != null && protocolVersion != session.protocolVersion) {
            ", which negotiated ${session.protocolVersion} where the client still names $protocolVersion"
        } else {
            ""
        }
        return "[mcp-host] $name: adopted the client's session ${session.id.take(SESSION_TAG)} " +
            "on a new child$versions\n"
    }
}
