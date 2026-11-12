// The client wall shared by a hold and the turn watchdog (failure-handling slice 8).
package splice.head.hold.resources

import splice.core.util.ElapsedClock
import splice.upstream.retry.WALL_MARGIN_MS

/** The session's wall for one request: on the head's [clock], it falls at [endsAtMs], the request's start plus the
 *  client's timeout, or a compaction's own timer where that is sooner. */
internal class SessionWall(
    private val clock: ElapsedClock,
    private val endsAtMs: Long,
    private val marginMs: Long = WALL_MARGIN_MS,
) {
    fun leftMs(): Long = if (endsAtMs == Long.MAX_VALUE) Long.MAX_VALUE else endsAtMs - clock()

    /** Usable wait time, with the client's response margin already reserved. */
    fun holdLeftMs(): Long = if (endsAtMs == Long.MAX_VALUE) Long.MAX_VALUE else capAtMs() - clock()

    /** The watchdog's reading of the same wall, less its margin. */
    fun capAtMs(): Long = if (endsAtMs == Long.MAX_VALUE) Long.MAX_VALUE else endsAtMs - marginMs
}

/** The size of the request a held turn keeps in memory while it waits. */
internal fun interface KeptBytes {
    operator fun invoke(): Long
}
