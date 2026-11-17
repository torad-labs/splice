// NEW: server mode (docs/specs/server-mode.md, F2, F3, F12 and decision 6). A count of what happened in the last minute,
// and how long until one more fits under a cap: a person's turns (`rpm_per_person`) and one address's sign-ins
// (`[server.signin] rate_per_minute`) each count one, and the turns that ended in the minute count their tokens
// (`tpm_per_person`); all three are held to a per-minute cap with a `Retry-After`, and are counted the same way. Not
// thread-safe on its own: each owner counts under its own lock.
package splice.server

import java.time.Duration

internal class MinuteStarts {
    // When each happened and what it counts, in the order of when, oldest first.
    private val starts = ArrayDeque<Pair<Long, Long>>()
    private val minute = Duration.ofMinutes(1)

    /** What happened in the minute before [now] counts, all told. */
    fun count(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first().first >= minute.toMillis()) starts.removeFirst()
        return starts.sumOf { it.second }
    }

    /** The whole seconds, rounded up, until what the minute counts is under [cap] at [now], so one more fits; null
     *  when it is under now, or when [cap] is 0 (no cap). */
    fun waitFor(cap: Long, now: Long): Long? {
        var left = count(now)
        if (cap <= 0 || left < cap) return null
        // The moment enough of the oldest have left the minute for what is still in it to be under the cap.
        var freed = now
        for ((at, weight) in starts) {
            freed = at
            left -= weight
            if (left < cap) break
        }
        return minute.minusMillis(now - freed).plusSeconds(1).minusNanos(1).seconds.coerceIn(1, minute.seconds)
    }

    /** Counts [weight] at [at], which may be a moment before what was counted last. */
    fun add(at: Long, weight: Long = 1) {
        val place = starts.indexOfLast { it.first <= at } + 1
        starts.add(place, at to weight)
    }
}
