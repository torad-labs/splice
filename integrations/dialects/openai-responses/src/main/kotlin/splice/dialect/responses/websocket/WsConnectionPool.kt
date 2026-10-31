// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: a connection is registered/retired/failed only
// under `lock`, and a busy connection is never closed under its round.
package splice.dialect.responses.websocket

import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every conversation's sockets: get-or-connect, win the busy flag, and the two ways a round ends
 * for the registry — [release] back to the pool, or [failRound] and poison.
 *
 * No cap and no eviction (Marcos, 2026-10-27: the 32-socket LRU was a throttle). A conversation
 * holds one idle socket between rounds and opens another when a round of it is already in flight,
 * so no round is sent to SSE for want of a socket. Sockets leave only when they die, when a round
 * fails on them, or when [sweep] finds them idle past [idleCloseMs] or older than [maxSocketAgeMs].
 */
internal class WsConnectionPool(
    private val log: LogSink,
    private val logKeys: WsLogKeys,
    connector: WsConnector,
    /** The age at which an idle connection is retired rather than handed to a new round. */
    private val maxSocketAgeMs: Long,
    /** How long a connection may sit idle before the sweep closes it. */
    private val idleCloseMs: Long,
    /** The clock every socket's [WsPulse.openedAt] is stamped on, so an age is one subtraction. */
    private val clock: ElapsedClock,
) {
    private val connections = HashMap<String, MutableList<WsConnection>>()
    private val lock = Any()

    // Declared AFTER the registry it reads: the OpenSockets seam is only ever invoked from a
    // listener callback, long after construction, but property order keeps the dependency honest.
    private val factory = WsConnectionFactory(connector, log, logKeys, OpenSockets { openCount() }, clock)

    /** An idle live socket of [key], or a new one; null only when no socket could be opened (SSE round). */
    internal suspend fun acquire(
        key: String,
        session: String?,
        headers: Map<String, String>,
        wssUrl: String,
    ): WsConnection? {
        var conn = claimIdle(key)
        // The peer reaps idle sockets, and a round that arrives after a long idle is when that close lands. A socket
        // that died as this round took it is dropped and another taken, so the round never goes to HTTP for a socket
        // it never used.
        while (conn != null && !begin(key, conn)) conn = claimIdle(key)
        // A new socket that closed before its round began is dropped the same way, and the round is declined.
        return conn ?: connect(key, session, headers, wssUrl)?.takeIf { begin(key, it) }
    }

    /** A new ROUND holds [conn] now. False when the socket is already dead: it leaves the registry, and the round
     *  takes another. */
    private fun begin(key: String, conn: WsConnection): Boolean {
        // The previous round's terminal must not make this round's first frame look like a late tail. The fence is
        // per-round, and this is the one place a round starts.
        conn.terminalSeen.set(false)
        // Any abort still armed by the previous round is stale.
        conn.lease.incrementAndGet()
        conn.pulse.roundStarted()
        if (!conn.dead.get()) return true
        conn.pulse.roundEnded()
        synchronized(lock) { deregister(key, conn) }
        log("[ws] ${conn.label} closed as its round took it\n")
        return false
    }

    /** Win the busy flag on one of [key]'s idle sockets, retiring the aged ones it passes. */
    private fun claimIdle(key: String): WsConnection? {
        val retired = mutableListOf<WsConnection>()
        val claimed = synchronized(lock) {
            val sockets = connections[key] ?: return@synchronized null
            sockets.removeAll { it.dead.get() }
            var won: WsConnection? = null
            val each = sockets.iterator()
            while (won == null && each.hasNext()) {
                val conn = each.next()
                if (!conn.busy.compareAndSet(false, true)) continue
                if (aged(conn)) {
                    each.remove()
                    retired += conn
                } else {
                    won = conn
                }
            }
            if (sockets.isEmpty()) connections.remove(key)
            won
        }
        retire(retired.map { it to "retired at ${minutes(age(it))} minutes, before the 60-minute limit" })
        return claimed
    }

    private suspend fun connect(
        key: String,
        session: String?,
        headers: Map<String, String>,
        wssUrl: String,
    ): WsConnection? {
        val conn = factory.connect(key, session, headers, wssUrl) ?: return null
        // Busy before it is visible: a concurrent acquire of the key must never win the socket this
        // round opened for itself.
        conn.busy.set(true)
        synchronized(lock) { connections.getOrPut(key) { mutableListOf() } += conn }
        log("[ws] ${conn.label} connected (generation=${conn.generation})\n")
        return conn
    }

    /** Return the connection to the pool: clear its busy flag. The seam round-side code
     *  (WsRoundStream) uses instead of reaching into [connections] / [lock] directly. */
    internal fun release(key: String, conn: WsConnection) {
        conn.pulse.roundEnded()
        // A dead socket is deregistered while it is still BUSY, and its flag is never cleared: clearing first left a
        // window where a concurrent acquire won a socket this was about to drop, and that round rode SSE for nothing.
        val dead = conn.dead.get()
        synchronized(lock) { if (dead) deregister(key, conn) }
        if (!dead) conn.busy.set(false)
    }

    internal fun failRound(conn: WsConnection, key: String) {
        conn.kill()
        synchronized(lock) { deregister(key, conn) }
    }

    /** Close every idle socket past [idleCloseMs] or [maxSocketAgeMs]. The busy CAS reserves each one
     *  first, as [claimIdle] does, so a concurrent acquire can never win a socket this is about to close. */
    internal fun sweep() {
        val idle = mutableListOf<WsConnection>()
        val aged = mutableListOf<WsConnection>()
        synchronized(lock) {
            val keys = connections.entries.iterator()
            while (keys.hasNext()) {
                val sockets = keys.next().value
                sockets.removeAll { it.dead.get() }
                val each = sockets.iterator()
                while (each.hasNext()) {
                    val conn = each.next()
                    val bucket = when {
                        aged(conn) -> aged
                        clock() - conn.pulse.idleSince >= idleCloseMs -> idle
                        else -> null
                    }
                    if (bucket != null && conn.busy.compareAndSet(false, true)) {
                        each.remove()
                        bucket += conn
                    }
                }
                if (sockets.isEmpty()) keys.remove()
            }
        }
        // Every socket is closed before any line is written, so a failing log cannot leave one taken out of the
        // registry and never closed.
        (aged + idle).forEach(WsConnection::kill)
        retire(aged.map { it to "retired at ${minutes(age(it))} minutes, before the 60-minute limit" })
        retire(idle.map { it to "closed after ${minutes(clock() - it.pulse.idleSince)} idle minutes" })
    }

    private fun retire(sockets: List<Pair<WsConnection, String>>) {
        sockets.forEach { (conn, _) -> conn.kill() }
        sockets.forEach { (conn, why) -> log("[ws] ${conn.label} $why\n") }
    }

    private fun deregister(key: String, conn: WsConnection) {
        val sockets = connections[key] ?: return
        sockets.removeAll { it === conn }
        if (sockets.isEmpty()) connections.remove(key)
    }

    private fun aged(conn: WsConnection): Boolean = age(conn) >= maxSocketAgeMs

    private fun age(conn: WsConnection): Long = clock() - conn.pulse.openedAt

    private fun minutes(ms: Long): Long = ms.milliseconds.inWholeMinutes

    private fun openCount(): Int = synchronized(lock) { connections.values.sumOf { it.size } }
}
