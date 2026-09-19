// NEW: v0.4.0 FEATURES.md §8 — the client sessions of the MCP host — one per Claude Code
// session per hosted server — and the per-session notification stream. Owns every question the
// host asks about liveness (open streams, last activity) so idle reaping reads one truth.
package splice.control.mcp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val STREAM_BUFFER = 256

/** What a client that fell [STREAM_BUFFER] notifications behind must not miss: that the lists it
 *  caches may have changed. The dropped backlog is replaced by these, so a `tools/list_changed`
 *  that was queued is never lost silently — the client re-lists once it catches up. */
private val LIST_INVALIDATIONS = listOf("tools", "prompts", "resources").map { kind ->
    """{"jsonrpc":"2.0","method":"notifications/$kind/list_changed"}"""
}

/** One client session on one hosted server; the stream channel carries the child's notifications. */
internal class McpSession(val id: String, val server: String, val initResult: JsonObject) {
    val stream: Channel<String> = Channel(STREAM_BUFFER, BufferOverflow.SUSPEND)
    val openStreams = AtomicInteger()
    private val codec = JsonRpcCodec()

    @Volatile var overflowed: Boolean = false
        private set

    /** Only list invalidations coalesce. Losing a resource update cannot be repaired by re-listing:
     *  end that session explicitly so its next request reinitializes instead of trusting stale state. */
    @Synchronized
    fun offer(text: String) {
        val sent = stream.trySend(text)
        if (sent.isSuccess || sent.isClosed) return
        val backlog = generateSequence { stream.tryReceive().getOrNull() }.toList() + text
        val methods = LIST_INVALIDATIONS.mapNotNull { codec.parse(it)?.let(codec::method) }.toSet()
        if (backlog.all { codec.parse(it)?.let(codec::method) in methods }) {
            LIST_INVALIDATIONS.forEach { stream.trySend(it) }
            stream.trySend(text)
        } else {
            overflowed = true
            stream.trySend(
                """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"error",""" +
                    """"data":"notification stream overflow; reinitialize this MCP session"}}""",
            )
            stream.close()
        }
    }

    /** The version the child negotiated at this session's initialize; later requests must name it. The
     *  child's answer, verbatim: the server picks the protocol version (MCP: a client that cannot
     *  speak it disconnects); inventing the client's requested one would promise a dialect the
     *  child never negotiated. */
    val protocolVersion: String? = JsonScalars.str(initResult, "protocolVersion")

    @Volatile var lastActivity: Long = 0L
        private set

    fun touch(now: Long) {
        lastActivity = now
    }

    /** Busy = streaming now, or spoke within [idleMillis] of [now]. */
    fun busy(now: Long, idleMillis: Long): Boolean = openStreams.get() > 0 || now - lastActivity < idleMillis
}

internal class McpSessions(private val clock: HostClock) {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    /** When the last session on a server ended: a server with no session is idle from THEN, not
     *  from forever, so the next 60 s sweep does not kill the process the next session would reuse
     *  (review 2026-09-14: cross-session reuse is the point of hosting). */
    private val ended = ConcurrentHashMap<String, Long>()

    /** A session on [server], minted with the child's [initResult]. */
    fun create(server: String, initResult: JsonObject): McpSession {
        val session = McpSession(UUID.randomUUID().toString(), server, initResult)
        touch(session)
        sessions[session.id] = session
        return session
    }

    /** The session when [id] exists AND belongs to [server]; a session never crosses servers. */
    fun get(server: String, id: String?): McpSession? =
        id?.let(sessions::get)?.takeIf { it.server == server && !it.overflowed }

    /** A closed overflowed stream still owns its count until the HTTP pump's finally runs. */
    fun closeStream(server: String, id: String?) {
        id?.let(sessions::get)?.takeIf { it.server == server }?.let {
            touch(it)
            it.openStreams.decrementAndGet()
        }
    }

    fun end(server: String, id: String?): McpSession? {
        val session = get(server, id) ?: return null
        sessions.remove(session.id)
        session.stream.close()
        ended[server] = clock.millis()
        return session
    }

    fun touch(session: McpSession) {
        session.touch(clock.millis())
    }

    fun forServer(server: String): List<McpSession> = sessions.values.filter { it.server == server }

    /** The registry holds its lease lock: no request or stream attachment can race this retirement. */
    fun expire(server: String, now: Long, idleMillis: Long) {
        forServer(server).filter { it.overflowed || !it.busy(now, idleMillis) }.forEach { session ->
            sessions.remove(session.id)
            session.stream.close()
            // Expiration observes old activity; unlike DELETE, it does not start another idle window.
            ended.merge(server, session.lastActivity, ::maxOf)
        }
    }

    fun dropServer(server: String) {
        ended.remove(server)
        forServer(server).forEach { s ->
            sessions.remove(s.id)
            s.stream.close()
        }
    }

    fun fanOut(server: String, text: String) {
        forServer(server).forEach { it.offer(text) }
    }

    /** One session's own notification (a progress update for its request); nobody else sees it. */
    fun deliver(server: String, sessionId: String, text: String) {
        get(server, sessionId)?.offer(text)
    }

    /** Busy = some session streams, some session spoke within [idleMillis], or the last one ended within it. */
    fun busy(server: String, now: Long, idleMillis: Long): Boolean =
        forServer(server).any { it.busy(now, idleMillis) } || ended[server]?.let { now - it < idleMillis } == true

    fun streaming(server: String): Boolean = forServer(server).any { it.openStreams.get() > 0 }

    fun lastActivity(server: String): Long =
        maxOf(forServer(server).maxOfOrNull { it.lastActivity } ?: 0L, ended[server] ?: 0L)
}
