// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: a connection is registered/evicted/failed only
// under `lock`; the race re-check (a LIVE predecessor always wins a concurrent connect) and the
// idle-only eviction (a busy connection is never evicted mid-round) are both unchanged.
package splice.dialect.responses

import splice.core.util.LogSink

/**
 * The bounded per-key connection registry: get-or-connect, win the busy flag, and the two ways a
 * round ends for the registry — [release] back to the pool, or [failRound] and poison.
 *
 * LRU by round-completion (touched on successful reuse); oldest evicted at the cap. Same
 * bounded-registry shape as ReasoningCache — one instance per provider, keys are conversation
 * keys, and an evicted entry costs a reconnect (status quo full-send), never an error.
 */
internal class WsConnectionPool(
    private val maxConnections: Int,
    private val log: LogSink,
    private val logKeys: WsLogKeys,
    connector: WsConnector,
) {
    private val factory = WsConnectionFactory(connector, log, logKeys)
    private val connections = LinkedHashMap<String, WsConnection>()
    private val lock = Any()

    /** Get-or-connect, and win the busy flag — or null (SSE round). */
    internal suspend fun acquire(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val existing = synchronized(lock) { connections[key]?.takeIf { !it.dead.get() } }
        val conn = existing ?: connect(key, headers, wssUrl) ?: return null
        if (!conn.busy.compareAndSet(false, true)) {
            // A concurrent round of the SAME conversation is already on the socket — never
            // interleave two response.create frames on one connection; the second rides SSE.
            log("[ws] ${logKeys.logKey(key)} busy — concurrent round rides SSE\n")
            return null
        }
        // Lost the race with a tear between the registry read and the busy win.
        if (conn.dead.get()) conn.busy.set(false)
        // A NEW round begins: the previous round's terminal must not make this round's first frame
        // look like a late tail. The fence is per-round, and this is the one place a round starts.
        conn.terminalSeen.set(false)
        // A new ROUND holds it now, so any abort still armed by the previous one is stale.
        conn.lease.incrementAndGet()
        return conn.takeIf { !it.dead.get() }
    }

    private suspend fun connect(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val conn = factory.connect(key, headers, wssUrl) ?: return null
        // RACE (review of #72): two callers can both miss the lookup in acquire() and both connect.
        // Replacing unconditionally meant the SECOND registration killed the first caller's socket —
        // which may already have won `busy` and started streaming — aborting a live response. Under
        // the lock we now re-check: a LIVE predecessor wins and our socket is discarded; only a dead
        // one is replaced.
        var winner = conn
        val evicted = synchronized(lock) {
            val existing = connections[key]
            if (existing != null && !existing.dead.get()) {
                winner = existing
                return@synchronized null
            }
            connections.remove(key)?.also { it.kill() } // only ever a DEAD predecessor
            connections[key] = conn
            // Only an IDLE connection may be evicted: an entry stays registered for the whole
            // of its round, so evicting by pure age could abort an in-flight response
            // (review of #72). With every connection busy the cap is soft until release() makes
            // one idle and trims the overshoot.
            removeOldestIdle(exceptKey = key)
        }
        if (winner !== conn) {
            // Lost the connect race: close our redundant socket and use the live one.
            conn.kill()
            return winner
        }
        evicted?.kill()
        log("[ws] ${logKeys.logKey(key)} connected (generation=${conn.generation})\n")
        return conn
    }

    /** Return the connection to the pool: clear its busy flag and touch it MRU. The seam round-side
     *  code (WsRoundStream) uses instead of reaching into [connections] / [lock] directly. */
    internal fun release(key: String, conn: WsConnection) {
        conn.busy.set(false)
        val evicted = synchronized(lock) { // touch: completed rounds move their connection to MRU
            // Identity-guarded like failRound below: by the time a round ends, the key may hold a
            // DIFFERENT connection (this one was killed and a successor registered), and an
            // unconditional remove+reinsert would promote that stranger to MRU on our round's
            // completion — skewing which entry the cap evicts next.
            if (connections[key] === conn) connections.remove(key)?.let { connections[key] = it }
            removeOldestIdle()
        }
        evicted?.kill()
    }

    /** Caller holds [lock]. A burst may exceed the soft cap only while every entry is busy; each
     *  later release removes one oldest idle entry until the registry is bounded again. The busy CAS
     *  reserves the victim before removal: acquire() may already hold its reference outside [lock],
     *  and must lose its own CAS rather than return a socket this trim is about to kill. */
    private fun removeOldestIdle(exceptKey: String? = null): WsConnection? {
        if (connections.size <= maxConnections) return null
        val idleKey = connections.entries.firstOrNull {
            it.key != exceptKey && it.value.busy.compareAndSet(false, true)
        }?.key
        return idleKey?.let { connections.remove(it) }
    }

    internal fun failRound(conn: WsConnection, key: String) {
        conn.kill()
        synchronized(lock) { if (connections[key] === conn) connections.remove(key) }
    }
}
