// NEW: V4-274 — whether THIS head has answered a session since it took the session over, which is
// what decides whose turn a status-line post's usage measured. Claude Code fills
// `context_window.current_usage` from the newest message of the transcript it loaded, whoever
// answered it (cli 2.1.283: the newest message carrying usage), and a resume keeps the session id.
// So right after `-r` onto another head the post carries the OLD head's last turn: claudex drew
// '87k/272k · 32%' after resuming a claude-splice session and '20k/272k · 8%' after its own first
// turn (take-resume-3, 2026-09-26), and the context seemed to shrink. One Claude Code process talks
// to one head, so from this head's first turn on, the usage is its own.
//
// Two facts decide it. The head's own perf rows count the session's turns on this head (the running
// total kept as rows are appended, else the byte-bounded tail). The route sees every head's posts,
// so this also remembers which head last posted each session: a session that comes back from another
// head (claude-splice, then claudex, then claude-splice again) is this head's only once its count
// passes what it was at the return, because its older turns predate the other head's. That memory
// lives for the daemon's life; after a restart the perf rows alone decide until a session moves.
package splice.usage.statusline

import splice.core.util.LruSizing
import splice.usage.perf.HeadSessionPerfSource

/** Sessions remembered at once, least recently posted dropped first: far above the live sessions a
 *  daemon serves, and a dropped one only falls back to the perf rows alone. */
private const val HELD_SESSIONS = 1024

/** One per route, shared by every head, so a session's move between heads is seen. Thread-safe:
 *  posts for many heads land concurrently on Ktor dispatcher threads. */
internal class StatuslineUsageOwner {
    /** The head that last posted a session, and how many turns of it that head held when it took the
     *  session over from another: 0 while it has held the session as long as this route remembers,
     *  and again once it has answered since. */
    private data class Holder(val head: String, val turnsAtTakeover: Int)

    private val holders = LinkedHashMap<String, Holder>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true)

    /** True when a post for [sessionId] on [head] carries usage this head did not produce: [turns]
     *  counts no turn of the session, or none since the head took it over from another. False when it
     *  cannot say: no session id, or a head that cannot read its turns per session. */
    fun unanswered(head: String, sessionId: String?, turns: HeadSessionPerfSource?): Boolean {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return false
        val count = turns?.let { countOf(it, session) } ?: 0
        return synchronized(holders) {
            val last = holders[session]
            val held = if (last?.head == head) last else Holder(head, if (last == null) 0 else count)
            val answered = count > held.turnsAtTakeover
            holders[session] = if (answered) held.copy(turnsAtTakeover = 0) else held
            if (holders.size > HELD_SESSIONS) holders.remove(holders.keys.first())
            turns != null && !answered
        }
    }

    /** The session's turns on this head: its running total when the head keeps one, which is every row
     *  since the total began, else the rows the tail holds. */
    private fun countOf(perf: HeadSessionPerfSource, session: String): Int =
        perf.sessionTotal(session)?.models?.values?.sumOf { it.turns }?.toInt()
            ?: perf.sessionTail(session).turns.size
}
