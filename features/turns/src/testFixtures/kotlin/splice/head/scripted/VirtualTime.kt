// NEW: failure-handling slice 0, the harness. One virtual clock behind every timing seam a head reads, so a route
// test moves hours of waiting in no real time.
package splice.head.scripted

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.Ticker
import splice.upstream.Waiter
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One clock behind a head's [Waiter], [Ticker], [WallClock] and [ElapsedClock], and behind the scripted provider's
 * delays, so a test moves hours of waiting with [advanceBy] while no real time passes.
 *
 * Manual by default: nothing moves until the test calls [advanceBy], and every wait parks until the clock reaches
 * its deadline. [awaitParked] tells the test a wait has begun, so a test never guesses with a sleep.
 *
 * With [autoAdvance], a one-shot wait (a [Waiter] wait or a [sleepUntil]) moves the clock to its own deadline and
 * returns at once: a test that does not care how long a backoff takes gets it instantly, and the clock still reads
 * the time it took. A [Ticker] never moves time in either mode, because a loop paced by one would spin the clock
 * forward on its own.
 */
public class VirtualTime(
    private val autoAdvance: Boolean = false,
    startWall: Instant = Instant.parse(DEFAULT_START),
) {
    private val startEpochMs: Long = startWall.toEpochMilli()
    private val lock = Any()
    private var passedMs = 0L
    private var closed = false
    private val sleepers = mutableListOf<Sleeper>()
    private val alarms = mutableListOf<Alarm>()
    private val parked = MutableStateFlow(0)

    /** Something a test does once the clock reads [dueMs]. */
    private data class Alarm(val dueMs: Long, val action: () -> Unit)

    /** One parked wait: woken with true when the clock reaches its due time, with false when the clock closes. */
    private class Sleeper(private val dueMs: Long) {
        private val woken = CompletableDeferred<Boolean>()

        fun isDue(nowMs: Long): Boolean = dueMs <= nowMs

        fun wake(reached: Boolean) {
            woken.complete(reached)
        }

        suspend fun await(): Boolean = woken.await()
    }

    /** The monotonic reading every elapsed-time seam takes, in milliseconds. */
    public val elapsed: ElapsedClock = ElapsedClock { ELAPSED_ORIGIN_MS + read() }

    /** The epoch reading every wall-clock seam takes, in milliseconds. */
    public val wall: WallClock = WallClock { startEpochMs + read() }

    /** A one-shot wait on this clock. */
    public val waiter: Waiter = Waiter { ms -> spendUntil(read() + ms.coerceAtLeast(0L)) }

    /** A loop's pace on this clock; false once the clock is closed, so the loop ends. */
    public val ticker: Ticker = Ticker { ms -> park(read() + ms.coerceAtLeast(0L)) }

    /** How far the clock has moved since it was made. */
    public fun passed(): Duration = read().milliseconds

    /** Runs [action] once the clock reads [passed] since it was made, before any wait due then wakes, so a test changes
     *  the world at a moment of virtual time: a server that comes back 43 s into a turn. Runs at once when the clock
     *  is already there. */
    public fun at(passed: Duration, action: () -> Unit) {
        val now = synchronized(lock) {
            (passedMs >= passed.inWholeMilliseconds).also { due ->
                if (!due) alarms += Alarm(passed.inWholeMilliseconds, action)
            }
        }
        if (now) action()
    }

    /** Moves the clock forward and wakes every wait that is due. */
    public fun advanceBy(by: Duration) {
        require(!by.isNegative()) { "the clock never runs back: $by" }
        advanceTo(read() + by.inWholeMilliseconds)
    }

    /** Waits until the clock reads [passed] since it was made: true when it got there, false when the clock closed
     *  first. */
    public suspend fun sleepUntil(passed: Duration): Boolean = spendUntil(passed.inWholeMilliseconds)

    /** Returns once at least [count] waits are parked on this clock. */
    public suspend fun awaitParked(count: Int = 1) {
        parked.first { it >= count }
    }

    /** Ends every wait: a parked [Waiter] returns, a [Ticker] answers false, and later waits do not park. */
    public fun close() {
        val woken = synchronized(lock) {
            closed = true
            sleepers.toList().also { sleepers.clear() }
        }
        woken.forEach { it.wake(reached = false) }
    }

    private fun read(): Long = synchronized(lock) { passedMs }

    /** A one-shot wait until the clock reads [dueMs]: with [autoAdvance] the clock moves there first. */
    private suspend fun spendUntil(dueMs: Long): Boolean {
        if (autoAdvance) advanceTo(dueMs)
        return park(dueMs)
    }

    /** Parks until the clock reads [dueMs]: true when time got there, false when the clock closed first. A wait
     *  that is already due, or a closed clock, still yields once, so a loop polling on it lets others run. */
    private suspend fun park(dueMs: Long): Boolean {
        val sleeper = Sleeper(dueMs)
        val settled = synchronized(lock) {
            when {
                closed -> false
                passedMs >= dueMs -> true
                else -> {
                    sleepers += sleeper
                    null
                }
            }
        }
        if (settled != null) {
            yield()
            return settled
        }
        parked.update { it + 1 }
        try {
            return sleeper.await()
        } finally {
            synchronized(lock) { sleepers.remove(sleeper) }
            parked.update { it - 1 }
        }
    }

    private fun advanceTo(targetMs: Long) {
        val (rung, due) = synchronized(lock) {
            if (targetMs > passedMs) passedMs = targetMs
            val rung = alarms.filter { it.dueMs <= passedMs }.also { alarms.removeAll(it) }
            rung to sleepers.filter { it.isDue(passedMs) }.also { sleepers.removeAll(it) }
        }
        rung.forEach { it.action() }
        due.forEach { it.wake(reached = true) }
    }
}

/** Where a virtual wall clock starts when a test names no instant. */
private const val DEFAULT_START = "2026-10-26T12:00:00Z"

/** Where the elapsed reading starts: far from zero, as a monotonic reading is, so no reading looks like "never". */
private const val ELAPSED_ORIGIN_MS = 1_000_000_000L
