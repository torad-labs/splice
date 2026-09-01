// PORT-OF: the v35 watchdog pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline — the
// slow-prefill regression (silent LONGER than streamIdle but shorter than firstByteTimeout
// must NOT be reaped before the first byte), idle-after-first-byte reaped, total cap reaped,
// typed sentinel set before cancel.
//
// DR-7 moved the cap OUT of launchIn: that poller now reaps a single round so the turn can salvage
// and continue, and launchTotalCap owns the only whole-turn cancel. The sentinel is per-turn and
// sticky, so resetFirstByte clears a stale Idle between rounds while leaving TotalCap standing.
//
// CLOCK POLICY (HD-19): the two IDLE cases still ride a real clock with generous margins, because
// what they prove is idleness measured by InflightGate.Slot, whose clock is its own and outside this
// wave's seams. The two TOTAL-CAP cases do NOT: TurnWatchdog now takes an injected Ticker, so
// [VirtualTicks] advances an injected clock by exactly the interval the production loop asked for
// and returns instantly. Those two used to be the file's slowest (1.67s and ~0.4s of real sleeping);
// they are now microseconds AND stricter — the cadence itself is asserted rather than waited out.
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import splice.spi.InflightGate
import splice.spi.Ticker
import splice.spi.TurnWatchdog
import splice.spi.WatchdogFired
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WatchdogTest {

    /** HD-19: the Ticker seam wired to a virtual clock. Every tick records the interval the
     *  production loop asked for and advances `now` by exactly that much, so a cap that needs four
     *  polls is proven in four instant iterations instead of four real sleeps. Returning true keeps
     *  the loop as unbounded as ProcessTicker does — these loops still exit only by firing. */
    private class VirtualTicks {
        var now: Long = 0L
            private set
        val intervals = mutableListOf<Long>()
        val clock: () -> Long = { now }
        val ticker = Ticker { ms ->
            intervals.add(ms)
            now += ms
            true
        }
    }

    /** DR-7: [VirtualTicks] never stops, because every loop it was written for exits by FIRING.
     *  Proving a loop does NOT fire needs the other kind of ticker — one that returns false after
     *  [max] samples, which is the documented way to stop these loops. Without it the assertion
     *  "nothing fired" is written as an infinite instant loop, and the test JVM dies of heap
     *  exhaustion rather than failing. */
    private class BoundedTicks {
        var now: Long = 0L
            private set
        val clock: () -> Long = { now }
        private var taken = 0
        val ticker = Ticker { ms ->
            now += ms
            taken += 1
            taken < BOUNDED_SAMPLES
        }
    }

    private fun budget(firstByteMs: Long, idleMs: Long, capMs: Long) = WatchdogBudget(
        firstByteTimeout = firstByteMs.milliseconds,
        streamIdle = idleMs.milliseconds,
        totalCap = capMs.milliseconds,
    )

    @Test
    fun `prefill silence beyond streamIdle is NOT reaped before first byte - the v35 case`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val dog = TurnWatchdog(budget(firstByteMs = 5_000, idleMs = 300, capMs = 30_000))
            val cancelled = AtomicBoolean(false)
            val target = launch {
                try {
                    delay(10.seconds)
                } finally {
                    cancelled.set(true)
                }
            }
            val poller = dog.launchIn(this, slot, target)
            delay(900) // silent 3x streamIdle, still under firstByteTimeout
            assertNull(dog.fired, "prefill was reaped — the compaction-ate-my-quota regression")
            target.cancel()
            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `idle after first byte is reaped with a typed sentinel`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val dog = TurnWatchdog(budget(firstByteMs = 10_000, idleMs = 300, capMs = 30_000))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target)
            slot.touch()
            dog.markByte()
            delay(900)
            target.join()
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "expected Idle, got $fired")
            assertTrue((fired as WatchdogFired.Idle).sawFirstByte)
            assertTrue(target.isCancelled)
            poller.cancel()
            slot.release()
        }
    }

    // DR-7, from codex-splice's review: resetFirstByte's Idle-clear was comment-only. Deleting the
    // line left every Watchdog arm AND the real HeadServer acceptance green, which means the
    // behaviour was asserted nowhere at all.
    @Test
    fun `resetFirstByte clears a stale Idle so the next round is not born stalled - DR-7`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val dog = TurnWatchdog(budget(firstByteMs = 10_000, idleMs = 300, capMs = 30_000))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target)
            slot.touch()
            dog.markByte()
            delay(900)
            target.join()
            assertTrue(dog.fired is WatchdogFired.Idle, "setup: the round must actually have been reaped")
            poller.cancel()
            // The sentinel is sticky so the terminal decision can name why the round died. Left set,
            // it makes every LATER round of the same turn terminate as stalled no matter how healthy
            // it is — and a salvaged round is by definition followed by another round.
            dog.resetFirstByte()
            assertNull(dog.fired, "a salvaged round must not leave the next one born stalled")
            slot.release()
        }
    }

    // The other half, and the reason it is a CAS against the observed Idle rather than a set(null):
    // totalCap is a WHOLE-TURN verdict, and no new round may erase it.
    @Test
    fun `resetFirstByte preserves a TotalCap verdict - DR-7`() {
        runBlocking {
            val ticks = VirtualTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            val capPoller = dog.launchTotalCap(this, target)
            target.join()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "setup: expected TotalCap, got ${dog.fired}")
            dog.resetFirstByte()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "the whole-turn verdict is not a round's to erase")
            capPoller.cancel()
        }
    }

    // codex-splice named the consequence, and it is the one that actually bites: firedRef is written
    // by compareAndSet(null, …), so a sentinel left set from an earlier round SILENTLY BLOCKS the
    // whole-turn cap from ever recording its own verdict. The turn would then be cancelled by the
    // cap while reporting an idle stall from a round that already ended.
    @Test
    fun `a stale Idle would block the later TotalCap from recording - DR-7`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val dog = TurnWatchdog(budget(firstByteMs = 10_000, idleMs = 300, capMs = 1_200))
            val stalled = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, stalled)
            slot.touch()
            dog.markByte()
            delay(900)
            stalled.join()
            assertTrue(dog.fired is WatchdogFired.Idle, "setup: round one must be reaped")
            poller.cancel()
            dog.resetFirstByte()
            val next = launch { delay(10.seconds) }
            val capPoller = dog.launchTotalCap(this, next)
            next.join()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "the cap must record its own verdict, got ${dog.fired}")
            capPoller.cancel()
            slot.release()
        }
    }

    // DR-7 REVERSES the arm that stood here (`total cap reaps even a lively stream`, via launchIn).
    // launchIn is now ROUND-scoped: it cancels one round so the turn can salvage and continue. A
    // whole-turn verdict raised from there would reap a round and let the fold loop open the next
    // — spending past the one budget whose name means stop — so the cap check moved out entirely.
    @Test
    fun `launchIn never raises a whole-turn TotalCap - launchTotalCap owns that cancel - DR-7`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val ticks = BoundedTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            // lively and past first byte: real idle is ~0 against a 5s limit, so Idle cannot fire
            // and the virtual clock is far past the 600ms cap. Nothing is left that could.
            slot.touch()
            dog.markByte()
            val poller = dog.launchIn(this, slot, target)
            poller.join()
            assertNull(dog.fired, "a round-scoped poller must not raise a whole-turn verdict")
            assertTrue(ticks.now > 600, "the virtual clock really did run past the cap (${ticks.now}ms)")
            assertTrue(target.isActive, "and the turn it was handed was never cancelled")
            target.cancel()
            slot.release()
        }
    }

    // The other half: the cap still reaps a stream that is chattering happily. launchTotalCap never
    // consults the slot, so liveliness cannot buy a turn extra time — which is exactly why it, and
    // not the idle poller, is the right owner of the whole-turn cancel.
    @Test
    fun `the whole-turn cap still reaps a lively stream - DR-7`() {
        runBlocking {
            val ticks = VirtualTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch {
                while (true) {
                    dog.markByte()
                    delay(50)
                }
            }
            val capPoller = dog.launchTotalCap(this, target)
            target.join()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "expected TotalCap, got ${dog.fired}")
            assertTrue(target.isCancelled)
            capPoller.cancel()
        }
    }

    @Test
    fun `turn-scoped cap poller reaps with NO open stream - the NF-03 case`() {
        runBlocking {
            // No slot, no launchIn: this is the connect/backoff/refresh/between-rounds window the
            // stream-scoped poller never covers. launchTotalCap alone must fire the typed sentinel.
            val ticks = VirtualTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 400),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            val capPoller = dog.launchTotalCap(this, target)
            target.join()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "expected TotalCap, got ${dog.fired}")
            // The cap poller must pace itself against totalCap, not streamIdle: capThird is 133ms,
            // coerced up to the 250ms floor, so a 400ms cap is sampled on the SECOND tick. Waiting
            // this out in real time is what made the case slow AND blind to that pacing rule.
            assertEquals(listOf(250L, 250L), ticks.intervals)
            capPoller.cancel()
        }
    }

    @Test
    fun `turn-scoped cap poller stays silent under the cap`() {
        runBlocking {
            val dog = TurnWatchdog(budget(2_000, 2_000, 60_000))
            val target = launch { delay(150) }
            val capPoller = dog.launchTotalCap(this, target)
            target.join()
            capPoller.cancel()
            assertNull(dog.fired, "a turn well under totalCap must not be reaped")
        }
    }

    @Test
    fun `clean exit - poller cancelled, nothing fired`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val dog = TurnWatchdog(budget(2_000, 2_000, 5_000))
            val target = launch { delay(100) }
            val poller = dog.launchIn(this, slot, target)
            target.join()
            poller.cancel()
            assertNull(dog.fired)
            slot.release()
        }
    }

    @Test
    fun `poll interval floors and caps`() {
        assertEquals(250, TurnWatchdog(budget(1, 300, 1)).pollInterval().inWholeMilliseconds)
        assertEquals(15_000, TurnWatchdog(budget(1, 600_000, 1)).pollInterval().inWholeMilliseconds)
        assertEquals(1_000, TurnWatchdog(budget(1, 3_000, 1)).pollInterval().inWholeMilliseconds)
    }
}

// DR-7: how many samples [WatchdogTest.BoundedTicks] allows before it stops its loop. Four is well
// past every cap in this file's budgets, so "nothing fired" is proven against a clock that really
// did run out rather than one that never got going.
private const val BOUNDED_SAMPLES = 4
