// NEW (v0.4.0, FEATURES.md §8): the client sessions of the MCP host — one per Claude Code
// session per hosted server — and the per-session notification stream. Owns every question the
// host asks about liveness (open streams, last activity) so idle reaping reads one truth.
package splice.control.mcp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val STREAM_BUFFER = 256

/** One client session on one hosted server; the stream channel carries the child's notifications. */
internal class McpSession(val id: String, val server: String, val initResult: JsonObject) {
    val stream: Channel<String> = Channel(STREAM_BUFFER, BufferOverflow.DROP_OLDEST)
    val openStreams = AtomicInteger()

    /** The version the child negotiated at this session's initialize; later requests must name it. The
     *  child's answer, verbatim: the server picks the protocol version (MCP: a client that cannot
     *  speak it disconnects); inventing the client's requested one would promise a dialect the
     *  child never negotiated. */
    val protocolVersion: String? = (initResult["protocolVersion"] as? JsonPrimitive)?.content

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

    /** A session on [server], minted with the child's [initResult]. */
    fun create(server: String, initResult: JsonObject): McpSession {
        val session = McpSession(UUID.randomUUID().toString(), server, initResult)
        touch(session)
        sessions[session.id] = session
        return session
    }

    /** The session when [id] exists AND belongs to [server]; a session never crosses servers. */
    fun get(server: String, id: String?): McpSession? = id?.let(sessions::get)?.takeIf { it.server == server }

    fun end(server: String, id: String?): McpSession? {
        val session = get(server, id) ?: return null
        sessions.remove(session.id)
        session.stream.close()
        return session
    }

    fun touch(session: McpSession) {
        session.touch(clock.millis())
    }

    fun forServer(server: String): List<McpSession> = sessions.values.filter { it.server == server }

    fun dropServer(server: String) {
        forServer(server).forEach { s ->
            sessions.remove(s.id)
            s.stream.close()
        }
    }

    fun fanOut(server: String, text: String) {
        forServer(server).forEach { it.stream.trySendBlocking(text) }
    }

    /** Busy = some session streams, or some session spoke within [idleMillis]. */
    fun busy(server: String, now: Long, idleMillis: Long): Boolean = forServer(server).any { it.busy(now, idleMillis) }

    fun streaming(server: String): Boolean = forServer(server).any { it.openStreams.get() > 0 }

    fun lastActivity(server: String): Long = forServer(server).maxOfOrNull { it.lastActivity } ?: 0L
}
