// NEW: v0.4.0 FEATURES.md §8 — the client sessions of the MCP host — one per Claude Code
// session per hosted server — and the per-session notification stream. Owns every question the
// host asks about liveness (open streams, last activity) so idle reaping reads one truth.
package splice.control.mcp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val STREAM_BUFFER = 256

// why: the ceiling on remembered DELETEd ids (V4-148). One client session ends one id, so this is
// thousands of Claude Code sessions' worth of memory for a per-id boolean, and a client whose id fell
// out is one that stopped speaking long enough for the host to forget it.
private const val MAX_DELETED_IDS = 4096

/** What a client that fell [STREAM_BUFFER] notifications behind must not miss: that the lists it
 *  caches may have changed. The dropped backlog is replaced by these, so a `tools/list_changed`
 *  that was queued is never lost silently — the client re-lists once it catches up. */
private val LIST_INVALIDATIONS = listOf("tools", "prompts", "resources").map { kind ->
    """{"jsonrpc":"2.0","method":"notifications/$kind/list_changed"}"""
}

/** One client session on one hosted server; the stream channel carries the child's notifications.
 *  [adopted] marks a session minted under an id the CLIENT already held (V4-148), never one this host
 *  issued: its handshake happened after the client last spoke, which is why the version check below
 *  reads it. */
internal class McpSession(
    val id: String,
    val server: String,
    val initResult: JsonObject,
    val adopted: Boolean = false,
) {
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

    // V4-148: ids the client ENDED, so adoption never resurrects one. Bounded, because a daemon runs
    // for weeks: the eldest fall out first, and an id that old belongs to a client long gone. Reached
    // under the map's own lock; LinkedHashMap evicts by insertion order.
    private val deleted = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > MAX_DELETED_IDS
        },
    )

    /** A session on [server], minted with the child's [initResult]. [id] is the client's own id when
     *  this is an adoption (V4-148); null mints a fresh one. */
    fun create(server: String, initResult: JsonObject, id: String? = null): McpSession {
        val session = McpSession(id ?: UUID.randomUUID().toString(), server, initResult, adopted = id != null)
        touch(session)
        // putIfAbsent, never put: two concurrent adoptions of one id (a client that retried) must keep
        // ONE session, or the loser's stream sits in nobody's map holding its child's notifications.
        val winner = sessions.putIfAbsent(session.id, session)
        if (winner != null) session.stream.close()
        return winner ?: session
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

    /** V4-148: may [id] be adopted — an id this host does not know, so one it issued before a restart,
     *  an idle reap or an eviction, or one a client invented. Refused for an id it still holds, which
     *  covers both an OVERFLOWED session (that one must reinitialize, by design — the notifications it
     *  lost cannot be replayed) and a session on another server, and refused for one the client ENDED,
     *  or DELETE would stop meaning ended. */
    fun adoptable(id: String?): Boolean = id != null && !sessions.containsKey(id) && !deleted.containsKey(id)

    /** DELETE: the client is done with this session, and its id is remembered as ended (V4-148), so no
     *  later request adopts it back. */
    fun end(server: String, id: String?): McpSession? {
        val session = get(server, id) ?: return null
        sessions.remove(session.id)
        deleted[session.id] = true
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
            // V4-148: an OVERFLOWED session must reinitialize — the notifications it missed cannot be
            // replayed — so its id is remembered as ended here too, or the sweep that forgets it would
            // hand the next request an adoption instead of the 404 that forces the reinitialize.
            if (session.overflowed) deleted[session.id] = true
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
