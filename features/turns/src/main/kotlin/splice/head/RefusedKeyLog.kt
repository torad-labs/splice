// NEW: Oct 26, 2026. After that day's key rotation a session was refused at a gateway head for 19 minutes while
// daemon.log stayed quiet, and its silence was read as nothing failing: ClientAuth answered the session's 401 and wrote
// nothing. Every local credential a head refuses is said here. A refused caller controls its headers and can loop, so
// the lines are bounded the way ForeignHostLog bounds foreign hosts: per session in time, and past a limit of named
// sessions by count.
package splice.head

import splice.core.util.ElapsedClock
import splice.core.util.LogSafe
import splice.core.util.LogSink

// why: Marcos's bound (Oct 26, 2026): a session is said when it is first refused and again at most every ten minutes
// while it keeps being refused, so a stranded session reads as one in the log however often its client sends again
private const val SAY_AGAIN_MS = 600_000L

// why: a rotation strands every session launched before it at once, and this machine runs 20+ sessions at a time
// (Marcos, Oct 16, 2026); 64 names each of them with room to spare, and holds a caller that loops through made-up
// session ids to 64 lines every ten minutes. Past it the refusals are counted, the count said at each doubling.
private const val MAX_NAMED_SESSIONS = 64

/** What a line calls a refused request that carried no session header. */
private const val NO_SESSION = "a request that named no session"

/** Why a head refused a session's local credential, as its line says it. */
internal enum class KeyRefusal(val says: String) {
    /** A key `splice rotate-key` retired: the session was launched before the rotation. */
    RETIRED("it presented a key a rotation retired; `splice sessions relaunch` relaunches it with the key in force"),

    /** Neither header that carries a credential held one. */
    NO_CREDENTIAL("it presented no credential"),

    /** A credential that is none of splice's keys, in force or retired, such as a vendor's own key. */
    NOT_SPLICES("the credential it presented is not one of splice's keys"),
}

/**
 * Says in [log] that a head refused a session's local credential, naming the session and why, never the credential.
 * A session is said when it is first refused, and again at most every ten minutes while it keeps being refused. At
 * most [MAX_NAMED_SESSIONS] sessions are named at a time, the ones said in the last ten minutes: a refusal of any other
 * session while that many are is counted instead, and the count said at the first and at each doubling.
 */
internal class RefusedKeyLog(private val log: LogSink, private val clock: ElapsedClock) {
    private val lock = Any()

    /** Each named session, by its escaped id, and when its last line was said. */
    private val named = HashMap<String, Long>()

    /** Refusals counted past the named sessions, for the head's life, as ForeignHostLog counts. */
    private var counted = 0L

    fun refused(session: String?, why: KeyRefusal) {
        // The id is the caller's: escaped and bounded once (LogSafe), and only that form is written or kept.
        val name = session?.takeIf(String::isNotBlank)?.let(LogSafe::str)
        // Decided under the lock and written outside it: the lock keeps the map whole, and the sink writes a file.
        val line = synchronized(lock) { lineFor(name, why, clock()) } ?: return
        log(line)
    }

    /** The line a refusal of [name] at [now] is said with, or null when it adds none. Runs under [lock]. */
    private fun lineFor(name: String?, why: KeyRefusal, now: Long): String? {
        val key = name.orEmpty()
        val last = named[key]
        if (last != null && now - last < SAY_AGAIN_MS) return null
        if (last == null && !roomFor(now)) return countLine()
        named[key] = now
        val subject = name?.let { "session '$it'" } ?: NO_SESSION
        return "[auth] refused $subject: ${why.says}\n"
    }

    /** Whether a session not named yet can be: a session not said for ten minutes gives up its place first. */
    private fun roomFor(now: Long): Boolean {
        named.values.removeIf { now - it >= SAY_AGAIN_MS }
        return named.size < MAX_NAMED_SESSIONS
    }

    /** Counts a refusal past the named sessions; the line says the count at the first and at each doubling. */
    private fun countLine(): String? {
        counted++
        if (counted and (counted - 1) != 0L) return null
        return "[auth] refused more sessions within ten minutes than the $MAX_NAMED_SESSIONS it names; refusals of " +
            "the rest are counted, not named: $counted so far (said again at ${counted * 2})\n"
    }
}
