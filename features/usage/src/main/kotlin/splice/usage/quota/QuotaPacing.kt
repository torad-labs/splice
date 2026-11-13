// NEW: the time faculties of QuotaPoller, each as the pair that belongs together. QuotaCadence is how often the
// poller looks (the interval and the ticker that waits it out, with the early retry after a failure); QuotaClocks is
// what it reads the time from (wall time for the restart window, the monotonic clock for the probe floor);
// QuotaReadGap is how soon after one read the next may go out.
package splice.usage.quota

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker

// why: a boot's network comes up within seconds of the daemon, so the first retry is 10 s.
private const val QUOTA_RETRY_FIRST_MS = 10_000L

// why: eight doublings of 10 s is about 43 minutes, past any poll interval; the cap keeps the shift from overflowing.
private const val RETRY_DOUBLINGS_MAX = 8

// why: five minutes keeps the bars fresh without a request per turn; navigation allows one successful probe a minute.
internal const val QUOTA_POLL_INTERVAL_MS: Long = 5 * 60 * 1000L

// why: navigation cannot issue more than one successful probe per minute; a configured shorter poll interval stays
// its floor.
internal const val QUOTA_PROBE_FLOOR_MS = 60_000L

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

/** The gap a poller leaves between two reads of the usage endpoint.
 *
 *  It is the floor until the endpoint answers 429. On Nov 13, 2026 the logins of one command were held for more than
 *  four hours, and each was read once a minute so that a reset at the provider would be heard (HeldLoginReads). After
 *  23 minutes the endpoint refused, and from then on every login went the same round, on a ten-minute beat in the
 *  log: a refusal, the whole poll interval of silence that followed any refusal, five reads a minute apart, and the
 *  sixth refused. For half of every ten minutes a reset could not be heard.
 *
 *  A 429 now doubles the gap, up to the poll interval, and the gap stays there for as long as reads keep being asked
 *  for, so the endpoint is not walked back into its limit: at two minutes a login was inside what it allowed that
 *  morning. The gap returns to the floor at the first answer that follows a whole interval of quiet, which is the
 *  scheduled poll once no one is waiting. Any other refusal still waits the whole interval. */
internal class QuotaReadGap(private val cadence: QuotaCadence) {
    private var paceMs = floorMs()

    /** The shortest gap: the poll cadence, or the probe floor when the cadence is the longer of the two. Asked for
     *  rather than captured, so a cadence an operator changes governs the gap the next attempt sets. */
    fun floorMs(): Long = minOf(cadence.intervalMs(), QUOTA_PROBE_FLOOR_MS)

    /** The gap after a read that ended in [failure], null when it answered, [quietForMs] after the read before it. */
    fun after(failure: Throwable?, quietForMs: Long?): Long {
        val interval = maxOf(cadence.intervalMs(), floorMs())
        when {
            failure == null -> if (quietForMs == null || quietForMs >= interval) paceMs = floorMs()
            (failure as? QuotaEndpointRefused)?.status == HttpStatus.TOO_MANY_REQUESTS -> paceMs *= 2
            // Any other refusal, and a login splice cannot read at all, is an answer that seconds do not change.
            failure is QuotaEndpointRefused || failure is QuotaUnreadable -> return interval
            // An endpoint that could not be reached is asked again by the cadence's own early retry.
            else -> return floorMs()
        }
        paceMs = paceMs.coerceIn(floorMs(), interval)
        return paceMs
    }
}
