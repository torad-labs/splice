// NEW: the two time faculties of QuotaPoller, each as the pair that belongs together. QuotaCadence is how often the
// poller looks (the interval and the ticker that waits it out, with the early retry after a failure); QuotaClocks is
// what it reads the time from (wall time for the restart window, the monotonic clock for the probe floor).
package splice.usage.quota

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker

// why: a boot's network comes up within seconds of the daemon, so the first retry is 10 s.
private const val QUOTA_RETRY_FIRST_MS = 10_000L

// why: eight doublings of 10 s is about 43 minutes, past any poll interval; the cap keeps the shift from overflowing.
private const val RETRY_DOUBLINGS_MAX = 8

// why: five minutes keeps the bars fresh without a request per turn; navigation allows one successful probe a minute.
internal const val QUOTA_POLL_INTERVAL_MS: Long = 5 * 60 * 1000L

/** How long a poller waits between looks, asked for at every wait so a cadence an operator changes governs the next
 *  one without a restart (knob `quotaPollIntervalMs`). A wait already sleeping keeps the length it started with:
 *  cutting it short would probe the provider sooner than the cadence that was in force when it began. */
public fun interface QuotaIntervalMs {
    public operator fun invoke(): Long
}

public class QuotaCadence(
    public val intervalMs: QuotaIntervalMs = QuotaIntervalMs { QUOTA_POLL_INTERVAL_MS },
    private val ticker: Ticker = ProcessTicker(),
) {
    /** Waits out the pause after the poll that just ended; false when the ticker was cancelled. The full interval
     *  follows an answer; after the Nth failure in a row, 10 s doubled N-1 times, capped at the interval. */
    public suspend fun awaitNext(failures: Int): Boolean {
        // ONE reading for this wait: the cap on a failure's doubled retry is the same interval the answer path waits.
        val intervalMs = intervalMs()
        return ticker.awaitTick(
            when (failures) {
                0 -> intervalMs
                else -> minOf(intervalMs, QUOTA_RETRY_FIRST_MS shl minOf(failures - 1, RETRY_DOUBLINGS_MAX))
            },
        )
    }
}

public class QuotaClocks(
    private val wall: WallClock = WallClock(System::currentTimeMillis),
    private val elapsed: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    /** Wall time, epoch ms. */
    public fun now(): Long = wall()

    /** The monotonic reading a later [elapsedSince] measures from. */
    public fun mark(): Long = elapsed()

    /** Monotonic ms since [mark]. */
    public fun elapsedSince(mark: Long): Long = elapsed() - mark
}
