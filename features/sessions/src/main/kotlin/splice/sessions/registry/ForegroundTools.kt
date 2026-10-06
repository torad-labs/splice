// NEW: bounded foreground-tool activity supplements the session registry's existing freshness facts.
package splice.sessions.registry

import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.WallClock

// why: long foreground work must outlive the thirty-minute stale window, but a lost end cannot stay live forever.
private const val FOREGROUND_EXPIRY_MS = 12L * 60L * 60L * 1000L

// why: callbacks are optional; bound even a daemon that never receives SessionEnd or a registry read.
private const val MAX_FOREGROUND_SESSIONS = 1024

// why: cap concurrent calls and completion tombstones independently of how many sessions share the daemon.
private const val MAX_FOREGROUND_CALLS = 4096

/** An active opaque tool keeps its session heard until completion, expiry, session end or process exit. */
public class ForegroundTools(
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    private val expiresAfterMs: Long = FOREGROUND_EXPIRY_MS,
) : ForegroundToolActivity {
    private val sessions = LinkedHashMap<String, Session>()
    private val calls = LinkedHashMap<ToolKey, Call>()

    private data class Session(val owner: String, var heard: Long?, var closed: Boolean, var touched: Long)
    private data class ToolKey(val sessionId: String, val toolUseId: String)
    private data class Call(val at: Long, val active: Boolean)

    @Synchronized
    override fun record(call: ForegroundToolCall) {
        val at = clock()
        expire(at)
        when (call.phase) {
            ForegroundToolPhase.SESSION_START -> {
                val held = sessions[call.sessionId]
                if (held?.owner != call.owner || held.closed) {
                    calls.keys.removeAll { it.sessionId == call.sessionId }
                    sessions[call.sessionId] = Session(call.owner, at, closed = false, touched = at)
                }
            }
            ForegroundToolPhase.SESSION_END -> ended(call, at)
            ForegroundToolPhase.START, ForegroundToolPhase.END -> {
                call.toolUseId?.let { tool(call, it, at) }
            }
        }
        bound()
    }

    private fun ended(call: ForegroundToolCall, at: Long) {
        val held = sessions[call.sessionId]
        if (held == null) {
            sessions[call.sessionId] = Session(call.owner, null, closed = true, touched = at)
        } else if (held.owner == call.owner) {
            close(call.sessionId, at)
        }
    }

    private fun tool(call: ForegroundToolCall, toolUseId: String, at: Long) {
        val sessionId = call.sessionId
        val phase = call.phase
        val session = sessions.getOrPut(sessionId) { Session(call.owner, null, closed = false, touched = at) }
        if (session.closed || session.owner != call.owner) return
        val key = ToolKey(sessionId, toolUseId)
        val previous = calls[key]
        // Duplicate starts do not renew a missing-end lease. Completed ids are tombstones for
        // an END that arrives before START, because command hooks execute asynchronously.
        previous?.let {
            if (phase == ForegroundToolPhase.START || !it.active) return
        }
        calls[key] = Call(at, active = phase == ForegroundToolPhase.START)
        session.heard = at
        session.touched = at
        sessions.remove(sessionId)
        sessions[sessionId] = session
    }

    /** Current activity, or the last callback when no tool is active. */
    @Synchronized
    public fun heardAt(sessionId: String): Long? {
        val at = clock()
        expire(at)
        val session = sessions[sessionId]?.takeUnless { it.closed } ?: return null
        return if (calls.any { it.key.sessionId == sessionId && it.value.active }) at else session.heard
    }

    /** A gone process can never be revived by an outstanding asynchronous callback. */
    @Synchronized
    public fun forget(sessionId: String) {
        val at = clock()
        expire(at)
        close(sessionId, at)
        bound()
    }

    private fun close(sessionId: String, at: Long) {
        calls.keys.removeAll { it.sessionId == sessionId }
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(heard = null, closed = true, touched = at) }
    }

    private fun expire(at: Long) {
        calls.entries.removeAll { at - it.value.at >= expiresAfterMs }
        sessions.entries.removeAll { at - it.value.touched >= expiresAfterMs }
    }

    private fun bound() {
        if (sessions.size > MAX_FOREGROUND_SESSIONS) {
            val active = calls.filterValues { it.active }.keys.mapTo(hashSetOf()) { it.sessionId }
            while (sessions.size > MAX_FOREGROUND_SESSIONS) {
                val oldest = sessions.keys.firstOrNull { it !in active } ?: sessions.keys.first()
                sessions.remove(oldest)
                calls.keys.removeAll { it.sessionId == oldest }
            }
        }
        while (calls.size > MAX_FOREGROUND_CALLS) {
            val completed = calls.entries.firstOrNull { !it.value.active }?.key
            calls.remove(completed ?: calls.keys.first())
        }
    }
}
