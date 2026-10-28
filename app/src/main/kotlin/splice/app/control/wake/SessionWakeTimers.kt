package splice.app.control.wake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splice.core.util.WallClock
import splice.sessions.http.SessionEnding
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter

private const val MAX_PENDING_SESSIONS = 2_000

/** The deadline has arrived; the owner rechecks the live session and its latest ending before sending. */
internal fun interface SessionResetWake {
    suspend fun wake(session: String, ending: SessionEnding)
}

/** One deadline per session ending; a new ending cancels the old deadline without scanning or polling. */
internal class SessionWakeTimers(
    private val scope: CoroutineScope,
    private val clock: WallClock,
    private val wake: SessionResetWake,
    private val waiter: Waiter = ProcessWaiter(),
) {
    private data class Pending(val ending: SessionEnding, var job: Job?) {
        fun ignores(next: SessionEnding): Boolean = ending.atMs > next.atMs || ending == next
    }
    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()

    fun record(session: String, ending: SessionEnding) {
        // PerfStats and its restored rows preserve the full identity; the short display tag is never a wake key.
        val key = session
        val next = synchronized(lock) {
            val previous = pending[key]
            if (previous?.ignores(ending) == true) return
            previous?.job?.cancel()
            val reset = ending.resumeAtMs ?: ending.resetMs
            val job = reset?.let { scope.launch(start = CoroutineStart.LAZY) { waitFor(key, ending, it) } }
            pending.remove(key)
            // A clean ending is a tombstone: a delayed older callback cannot arm its reset again.
            pending[key] = Pending(ending, job)
            while (pending.size > MAX_PENDING_SESSIONS) pending.remove(pending.keys.first())?.job?.cancel()
            job
        }
        next?.start()
    }

    private suspend fun waitFor(session: String, ending: SessionEnding, reset: Long) {
        try {
            while (clock() < reset) waiter.wait((reset - clock()).coerceAtLeast(1L))
            wake.wake(session, ending)
        } finally {
            synchronized(lock) {
                pending[session]?.takeIf { it.ending == ending }?.job = null
            }
        }
    }
}
