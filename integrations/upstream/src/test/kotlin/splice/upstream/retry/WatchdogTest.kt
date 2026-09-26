// PORT-OF: the v35 watchdog pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline — the
// slow-prefill regression (silent LONGER than streamIdle but shorter than firstByteTimeout
// must NOT be reaped before the client has seen output), idle-after-first-frame reaped, total cap
// reaped, typed sentinel set before cancel.
//
// 2026-09-01: the tier is chosen by the round's CLIENT-FRAME probe, not by the first upstream byte.
// A Responses handshake (response.created) is bytes with no output, and flipping on it pinned every
// silent compaction to the short streamIdle tier — 109 live stalls in one day. The handshake arm
// below is that case; the v35 arm keeps its name and now passes the probe the driver would.
//
// DR-7 moved the cap OUT of launchIn: that poller now reaps a single round so the turn can salvage
// and continue, and launchTotalCap owns the only whole-turn cancel. The sentinel is per-turn and
// sticky, so resetRound clears a stale Idle between rounds while leaving TotalCap standing.
//
// CLOCK POLICY (HD-19, corrected by V4-139 on 2026-09-18): no arm here waits on the wall clock.
// The TOTAL-CAP cases run on [VirtualTicks]: TurnWatchdog takes an injected Ticker and clock, so a
// cap is proven in instant iterations and the cadence itself is asserted rather than waited out.
// The IDLE cases run on [SteppedTicks]. This header used to say they had to ride a real clock
// because InflightGate.Slot's clock had no seam. It was never true: InflightGate has taken an
// injectable clock since it was created (c2abdd3f, 2026-07-16; today an ElapsedClock at
// InflightGate.kt:40-44, which Slot.idleForMs reads), and the note kept these arms on ~7 s of real
// sleeping for no reason. The gate and the dog now share one virtual clock that the
// test steps: run to an instant, assert, change the world, release.
package splice.upstream.retry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.upstream.ClientFrameEmitted
import splice.upstream.Ticker
import splice.upstream.WsPathPulse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// DR-186: every OTHER arm in this file is self-bounding — it joins a `launch { delay(10.seconds) }`,
// so a watchdog that fails to cancel still returns in ten seconds and the assertion goes RED. The
// lively-stream arm is the one exception: its target loops forever by construction, so its join
// returns ONLY if the code under test cancels it. Without a backstop a regression there does not
// fail the suite, it wedges it — which is the whole of DR-186, in a shape that sweep did not
// enumerate because it looked for spin predicates rather than for the property.
private const val HANG_BACKSTOP_S = 60L

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

    /** V4-139: virtual time the TEST steps, for the idle arms. The gate's slot and the watchdog read
     *  the same [clock]; each poll tick advances it by the interval the production loop asked for,
     *  and a tick that would cross [horizon] parks until the test raises it. [runTo] steps to a
     *  virtual instant and returns once the poller is parked there; [release] lifts the horizon for
     *  an arm that ends by the poller FIRING. Everything runs on runBlocking's one thread, so the
     *  hand-off is deterministic: no real time passes between two virtual instants. */
    private class SteppedTicks {
        var now: Long = 0L
            private set
        private var horizon = 0L
        private var resume = CompletableDeferred<Unit>()
        private var parked = CompletableDeferred<Unit>()
        val clock = ElapsedClock { now }
        val ticker = Ticker { ms ->
            while (now + ms > horizon) {
                parked.complete(Unit)
                resume.await()
            }
            now += ms
            true
        }

        /** The only real time in these arms, and it is a BACKSTOP, never a wait: if the poller has
         *  fired and exited, nothing will ever park at [instantMs], and without this the arm hangs
         *  instead of going red. Measured: a mutant that flips the client-frame probe wedged the
         *  suite until it was killed. A healthy step returns in microseconds. */
        suspend fun runTo(instantMs: Long) {
            parked = CompletableDeferred()
            raise(instantMs)
            withTimeout(PARK_BACKSTOP_MS) { parked.await() }
        }

        fun release() = raise(Long.MAX_VALUE)

        private fun raise(to: Long) {
            horizon = to
            val waking = resume
            resume = CompletableDeferred()
            waking.complete(Unit)
        }
    }

    private fun budget(firstByteMs: Long, idleMs: Long, capMs: Long) = WatchdogBudget(
        firstByteTimeout = firstByteMs.milliseconds,
        streamIdle = idleMs.milliseconds,
        totalCap = capMs.milliseconds,
    )

    @Test
    fun `prefill silence beyond streamIdle is NOT reaped before the first client frame - the v35 case`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 5_000, idleMs = 300, capMs = 30_000),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val cancelled = AtomicBoolean(false)
            val target = launch {
                try {
                    delay(10.seconds)
                } finally {
                    cancelled.set(true)
                }
            }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false })
            ticks.runTo(900) // silent 3x streamIdle, still under firstByteTimeout
            assertNull(dog.fired, "prefill was reaped — the compaction-ate-my-quota regression")
            target.cancel()
            poller.cancel()
            slot.release()
        }
    }

    // THE COMPACT BUDGET (WatchdogBudget.forCompact): the pre-output tier is OFF, so a silent
    // compaction is never reaped by THIS poller — not at firstByteTimeout, not at totalCap, which is
    // launchTotalCap's cancel and the wall that alone may end it. Before this the tier was RAISED to
    // totalCap, and two pollers on one deadline flipped a coin over which verdict named the stall
    // (gate run 33575037270 on a loaded runner). Like the v35 arm above, it proves idleness as the
    // slot measures it, on the stepped virtual clock the slot shares.
    @Test
    fun `the compact budget never reaps pre-output silence from the idle poller, even past totalCap`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 5_000, idleMs = 300, capMs = 600).forCompact(),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val cancelled = AtomicBoolean(false)
            val target = launch {
                try {
                    delay(10.seconds)
                } finally {
                    cancelled.set(true)
                }
            }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false })
            ticks.runTo(1_200) // silent 4x streamIdle and 2x totalCap, no client frame yet
            assertNull(dog.fired, "the idle poller reaped a compaction's pre-output silence — the wall alone owns it")
            assertFalse(cancelled.get(), "the round was cancelled without a verdict")
            target.cancel()
            poller.cancel()
            slot.release()
        }
    }

    // THE 2026-09-01 CASE. The backend acknowledges at once (bytes touch the slot), then reasons in
    // silence with nothing yet shown to the client. Under the byte rule that ack flipped the tier and
    // the 300ms streamIdle reaped the round; under the frame rule the silence is judged on the 5s
    // first-output cap and survives. Then the first client frame lands, and the SAME silence is
    // reaped on streamIdle — the tier moved with the frame, exactly once, in the right direction.
    @Test
    fun `a handshake byte before any client frame keeps the first-output tier - the compaction stall case`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 5_000, idleMs = 300, capMs = 30_000),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val frameSeen = AtomicBoolean(false)
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { frameSeen.get() })
            slot.touch() // response.created: bytes on the wire, no output
            ticks.runTo(900) // silent 3x streamIdle after the ack, still under firstByteTimeout
            assertNull(dog.fired, "a handshake is not output — the ack must not flip the tier")
            assertTrue(target.isActive)
            frameSeen.set(true) // the first content frame reaches the client
            ticks.release() // and the same silence is now a mid-output stall
            target.join()
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "expected Idle once the client has seen output, got $fired")
            assertTrue((fired as WatchdogFired.Idle).sawClientFrame)
            assertEquals(300L, fired.limitMs, "the sentinel names the tier's cap")
            poller.cancel()
            slot.release()
        }
    }

    // THE 2026-09-05 CASE, the other half of the frame rule. gpt-6-astra reasons in silence for
    // 300-750 s before its first delta, and the ChatGPT backend pings the socket every ~20 s the whole
    // time; eleven turns that day were reaped at the 300 s tier with a server ping 5-20 s old on every
    // close line, each re-POSTed cold by the client. A round past its tier on a path that is still
    // being pinged is HELD, and reaped only once the pings stop too. The hold is recorded once and
    // logged once. Stepped virtual clock, like every other idle arm: it proves idleness as the slot
    // measures it.
    @Test
    fun `a silent round on a live path is held past its tier, and reaped once the path goes quiet`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val lines = mutableListOf<String>()
            val dog = TurnWatchdog(
                budget(firstByteMs = 300, idleMs = 300, capMs = 30_000),
                clock = ticks.clock,
                ticker = ticks.ticker,
                log = { lines += it },
            )
            val pingAgo = AtomicLong(8_000) // the last server ping is 8 s old: a live path
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false }, WsPathPulse { pingAgo.get() })
            ticks.runTo(900) // silent 3x the first-output tier
            assertNull(dog.fired, "a round on a path the server still pings was reaped")
            assertTrue(target.isActive)
            val held = checkNotNull(dog.held) { "the hold must be recorded" }
            assertTrue(held.idleMs >= 300L, "the idleness the poller judged: $held")
            assertEquals(300L, held.limitMs)
            assertEquals(8_000L, held.pingAgoMs)
            assertFalse(held.sawClientFrame)
            assertEquals(1, lines.size, "one line per turn, not one per poll: $lines")
            assertTrue("on a live path" in lines.single() && "holding the round" in lines.single(), lines.single())
            pingAgo.set(90_000) // three pings missed: the path itself has gone quiet
            ticks.release()
            target.join()
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "a quiet path is the stall the tier exists for, got $fired")
            assertEquals(300L, (fired as WatchdogFired.Idle).limitMs)
            assertEquals(1, lines.size, "the reap is the terminal's line, not this one's")
            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `idle after the first client frame is reaped with a typed sentinel`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 300, capMs = 30_000),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true })
            slot.touch()
            ticks.release() // the silence runs on until the poller reaps it
            target.join()
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "expected Idle, got $fired")
            assertTrue((fired as WatchdogFired.Idle).sawClientFrame)
            assertEquals(300L, fired.limitMs)
            assertTrue(target.isCancelled)
            poller.cancel()
            slot.release()
        }
    }

    // DR-7, from codex-splice's review: resetRound's Idle-clear was comment-only. Deleting the
    // line left every Watchdog arm AND the real HeadServer acceptance green, which means the
    // behaviour was asserted nowhere at all.
    @Test
    fun `resetRound clears a stale Idle so the next round is not born stalled - DR-7`() {
        runBlocking {
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 300, capMs = 30_000),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true })
            slot.touch()
            ticks.release() // the silence runs on until the poller reaps it
            target.join()
            assertTrue(dog.fired is WatchdogFired.Idle, "setup: the round must actually have been reaped")
            poller.cancel()
            // The sentinel is sticky so the terminal decision can name why the round died. Left set,
            // it makes every LATER round of the same turn terminate as stalled no matter how healthy
            // it is — and a salvaged round is by definition followed by another round.
            dog.resetRound()
            assertNull(dog.fired, "a salvaged round must not leave the next one born stalled")
            slot.release()
        }
    }

    // The other half, and the reason it is a CAS against the observed Idle rather than a set(null):
    // totalCap is a WHOLE-TURN verdict, and no new round may erase it.
    @Test
    fun `resetRound preserves a TotalCap verdict - DR-7`() {
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
            dog.resetRound()
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
            val ticks = SteppedTicks()
            val gate = InflightGate({ 0 }, clock = ticks.clock)
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 300, capMs = 1_200),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val stalled = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, stalled, ClientFrameEmitted { true })
            slot.touch()
            ticks.release() // round one's silence runs on until the idle poller reaps it
            stalled.join()
            assertTrue(dog.fired is WatchdogFired.Idle, "setup: round one must be reaped")
            poller.cancel()
            dog.resetRound()
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
            val slot = gate.admittedSlot()
            val ticks = BoundedTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            val target = launch { delay(10.seconds) }
            // lively and past the first client frame: real idle is ~0 against a 5s limit, so Idle
            // cannot fire and the virtual clock is far past the 600ms cap. Nothing is left that could.
            slot.touch()
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true })
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
    @Timeout(HANG_BACKSTOP_S) // DR-186: the target below never ends on its own; only the cap ends it
    fun `the whole-turn cap still reaps a lively stream - DR-7`() {
        runBlocking {
            val ticks = VirtualTicks()
            val dog = TurnWatchdog(
                budget(firstByteMs = 10_000, idleMs = 5_000, capMs = 600),
                clock = ticks.clock,
                ticker = ticks.ticker,
            )
            // Lively by construction, and never done: it yields forever, so only the cap ends it.
            val target = launch {
                while (true) {
                    yield()
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
            val target = launch { yield() } // any duration under the 60 s cap proves the same thing
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
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(budget(2_000, 2_000, 5_000))
            val target = launch { yield() } // any duration under the 5 s cap proves the same thing
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false })
            target.join()
            poller.cancel()
            assertNull(dog.fired)
            slot.release()
        }
    }

    @Test
    fun `poll interval floors and caps, paced to the tighter idle tier`() {
        assertEquals(250, TurnWatchdog(budget(300, 300, 1)).pollInterval().inWholeMilliseconds)
        assertEquals(15_000, TurnWatchdog(budget(600_000, 600_000, 1)).pollInterval().inWholeMilliseconds)
        assertEquals(1_000, TurnWatchdog(budget(3_000, 3_000, 1)).pollInterval().inWholeMilliseconds)
        // A first-output cap tighter than streamIdle paces the poll, or a pre-output stall would be
        // sampled only every streamIdle/3 — the SseRoundConsumeTest rig (1s / 20s) is that case.
        assertEquals(333, TurnWatchdog(budget(1_000, 20_000, 1)).pollInterval().inWholeMilliseconds)
        // Production: 300s first-output / 180s streamIdle still sits on the 15s ceiling.
        assertEquals(15_000, TurnWatchdog(budget(300_000, 180_000, 1)).pollInterval().inWholeMilliseconds)
    }
}

// DR-7: how many samples [WatchdogTest.BoundedTicks] allows before it stops its loop. Four is well
// past every cap in this file's budgets, so "nothing fired" is proven against a clock that really
// did run out rather than one that never got going.
private const val BOUNDED_SAMPLES = 4

// SteppedTicks.runTo's hang backstop — see its comment. Not a timing assumption: a step that has a
// poller to park returns instantly, and only a poller that has ALREADY exited can reach this.
private const val PARK_BACKSTOP_MS = 10_000L
