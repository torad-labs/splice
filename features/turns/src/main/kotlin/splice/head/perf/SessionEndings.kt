// NEW: Oct 10, 2026 (step 3, At limit and Signed out) — how each session's newest request ended, as its perf row did.
//
// A request splice turns away at admission (every account spent, a plan window spent, no credential) ends in
// milliseconds and never sits in LiveTurns, so the console cannot see it in flight. Its perf row is the only record,
// and reading the file per listing would scan hours of rows every five seconds. So the row is noted here as it is
// appended: memory only, newest row per session, bounded. A daemon restart forgets it, and the session's next
// request writes it again.
package splice.head.perf

/** One request's ending: its outcome tag, the account it ran on, when a spent window comes back (epoch seconds,
 *  null when the provider named none) and the row's own time. */
public data class SessionEnding(val outcome: String, val account: String?, val resetEpochSeconds: Long?, val ts: Long)

// why: a person runs tens of sessions a day, so this many covers every one the console lists with room to spare,
// and each note is a few short strings.
private const val MAX_SESSION_ENDINGS = 2_000

public class SessionEndings {
    private val lock = Any()

    // Guarded by [lock]. Insertion order is recency: a session is re-inserted on each of its rows.
    private val newest = LinkedHashMap<String, SessionEnding>()

    /** The row just appended for [sessionTag]. A row older than the one held (a streaming round's row is appended
     *  late) does not replace it. */
    public fun note(sessionTag: String, outcome: String, account: String?, resetEpochSeconds: Long?, ts: Long) {
        if (sessionTag.isEmpty()) return
        val ending = SessionEnding(outcome, account, resetEpochSeconds, ts)
        synchronized(lock) {
            val held = newest.remove(sessionTag)
            newest[sessionTag] = if (held != null && held.ts > ending.ts) held else ending
            while (newest.size > MAX_SESSION_ENDINGS) newest.remove(newest.keys.first())
        }
    }

    /** [sessionId]'s newest ending, matched the way PerfStats matches a row: the stored tag is a truncation of the
     *  id the caller holds. Null for a session with no row since this daemon started. */
    public fun endingFor(sessionId: String): SessionEnding? {
        if (sessionId.isEmpty()) return null
        return synchronized(lock) {
            newest.entries.firstOrNull { (tag, _) -> sessionId.startsWith(tag) }?.value
        }
    }
}
