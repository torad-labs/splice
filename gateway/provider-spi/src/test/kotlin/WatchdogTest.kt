// PORT-OF: the v35 watchdog pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline — the
// slow-prefill regression (silent LONGER than streamIdle but shorter than firstByteTimeout
// must NOT be reaped before the first byte), idle-after-first-byte reaped, total cap reaped,
// typed sentinel set before cancel.
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

    @Test
    fun `total cap reaps even a lively stream`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.acquire()
            val ticks = VirtualTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch {
                // lively: touch constantly so idle never fires
                while (true) {
                    slot.touch()
                    dog.markByte()
                    delay(50)
                }
            }
            val poller = dog.launchIn(this, slot, target)
            target.join()
            assertTrue(dog.fired is WatchdogFired.TotalCap, "expected TotalCap, got ${dog.fired}")
            // liveliness is real, not virtual: the slot was touched microseconds ago, so real idle
            // is ~0 against a 5s idle limit — the ONLY thing that can have fired is the cap.
            assertEquals(1_666L, ticks.intervals.first(), "streamIdle/3, coerced into 250ms..15s")
            poller.cancel()
            slot.release()
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
