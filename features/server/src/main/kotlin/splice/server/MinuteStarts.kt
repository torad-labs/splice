// NEW: server mode (docs/specs/server-mode.md, F2, F3 and decision 6). A count of what started in the last minute, and
// how long until one more fits under a cap: a person's turns (`rpm_per_person`) and one address's sign-ins
// (`[server.signin] rate_per_minute`) are both held to a per-minute cap with a `Retry-After`, and are counted the same
// way. Not thread-safe on its own: each owner counts under its own lock.
package splice.server

import java.time.Duration

internal class MinuteStarts {
    private val starts = ArrayDeque<Long>()
    private val minute = Duration.ofMinutes(1)

    /** How many started in the minute before [now]. */
    fun count(now: Long): Int {
        while (starts.isNotEmpty() && now - starts.first() >= minute.toMillis()) starts.removeFirst()
        return starts.size
    }

    /** The whole seconds, rounded up, until one more start fits under [cap] at [now]; null when it fits now, or when
     *  [cap] is 0 (no cap). */
    fun waitFor(cap: Int, now: Long): Long? {
        if (cap <= 0 || count(now) < cap) return null
        val left = minute.minusMillis(now - starts.first())
        return left.plusSeconds(1).minusNanos(1).seconds.coerceIn(1, minute.seconds)
    }

    fun add(now: Long) {
        starts.addLast(now)
    }
}
