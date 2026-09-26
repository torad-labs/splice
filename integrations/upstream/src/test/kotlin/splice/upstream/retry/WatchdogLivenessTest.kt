// NEW: V4-125 — the LIVENESS half of the idle tier (law: IDLE IS A PROBE, NEVER AN ERROR).
//
// The defect this closes: the SSE path passed NO path pulse, so [TurnWatchdog.launchIn] read its
// NEVER_PINGED_MS default and every SSE round past its tier was reaped BY CONSTRUCTION. The tier was
// a verdict served on a transport that had no way to answer it, which is how a silent-but-alive
// backend — a model reasoning, a prefill still running — became a client-visible error.
//
// Three facts are pinned here. The first is the fix; the second is the guard that keeps the fix from
// eating a neighbouring feature, and it exists because a first cut of this row DID break it; the
// third is what remains of the tier once it stops being a verdict.
//
//   1. A LIVE PATH HOLDS. The round is polled on, never reaped short of the whole-turn cap, and the
//      hold RECORDS WHAT PROVED IT — "last server ping" is a false statement on a transport that has
//      no heartbeat, and a log line that says it anyway is the kind of confidently-wrong comment
//      this campaign keeps finding.
//   2. A LIVE PATH DOES NOT GATE THE STALL TIER. That tier is a HEAL — cancel the round and resume it
//      from its own salvage, invisible to the client — not a verdict, so consulting the pulse about
//      it would silently disable V4-116 on every transport that reports a live path. Three
//      MidStreamTearContinuesTest cases caught exactly that during this row, which is why the guard
//      gets an arm of its own instead of being left to those three to cover by accident.
//   3. THE WHOLE-TURN CAP STILL ENDS A HELD ROUND, and is the ONLY thing that does: no idle tier ever
//      reaches the client as an error.
//
// CLOCK POLICY: real clock and real sleeps, matching WatchdogStallTierTest — what is under test is
// idleness as InflightGate.Slot measures it, and that clock is its own. Budgets are scaled so the
// slowest arm here is a couple of seconds.
package splice.upstream.retry

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import splice.upstream.ClientFrameEmitted
import splice.upstream.WsPathPulse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Long enough for several polls of a 300ms tier to have landed, and far below any budget here. */
private const val POLLS_SETTLE_MS = 900L

class WatchdogLivenessTest {

    private fun budget(firstByteMs: Long, idleMs: Long, capMs: Long, stallMs: Long? = null) = WatchdogBudget(
        firstByteTimeout = firstByteMs.milliseconds,
        streamIdle = idleMs.milliseconds,
        totalCap = capMs.milliseconds,
        stallReanchor = stallMs?.milliseconds ?: Duration.INFINITE,
    )

    /** The reading SseRoundConsume supplies for EVERY SSE round: the connection is open and has not
     *  errored, so the last liveness evidence is 0ms old. Named once, because a bare `{ 0L }` at
     *  each call site reads like a rig detail when it is in fact the production reading. */
    private fun openConnection(): WsPathPulse = WsPathPulse { 0L }

    @Test
    fun `an SSE round on an open connection is held, and the hold names the connection not a ping`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val lines = mutableListOf<String>()
            val dog = TurnWatchdog(budget(firstByteMs = 300, idleMs = 300, capMs = 30_000), log = { lines += it })
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(
                this,
                slot,
                target,
                ClientFrameEmitted { false },
                openConnection(),
                PathEvidence.OPEN_CONNECTION,
            )
            delay(POLLS_SETTLE_MS)

            assertNull(dog.fired, "a round on an open connection was reaped by the tier")
            assertTrue(target.isActive, "a held round must still be running")
            val held = checkNotNull(dog.held) { "the hold must be recorded for the turn line" }
            assertEquals(PathEvidence.OPEN_CONNECTION, held.evidence, "the evidence kind must travel with the hold")
            assertEquals(300L, held.limitMs, "the sentinel names the tier's cap")
            assertFalse(held.sawClientFrame, "a handshake is not output")

            val line = lines.single()
            assertTrue("upstream connection open" in line, line)
            assertFalse(
                "server ping" in line,
                "the SSE path has no heartbeat; reporting one is the false-comment failure in a log line: $line",
            )

            poller.cancel()
            slot.release()
        }
    }

    // FACT 2, and the arm this file exists for as much as fact 1. A first cut of V4-125 let the pulse
    // hold on EVERY tier, which quietly turned the stall tier off wherever a path was live — the
    // re-anchor never fired and three MidStreamTearContinuesTest cases went red. A heal must not be
    // gated on whether the patient is breathing; it is asked for because the round has stopped
    // producing, which is a different question from whether the socket is open.
    @Test
    fun `a live path does not gate the stall tier - a heal is not a verdict`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(firstByteMs = 300, idleMs = 300_000, capMs = 900_000, stallMs = 200))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(
                this,
                slot,
                target,
                ClientFrameEmitted { true },
                openConnection(),
                PathEvidence.OPEN_CONNECTION,
            )
            // Bounded, not a bare join: if the guard regresses, the failure should be an assertion
            // naming the cause rather than a ten-second hang inside a test that then reports nothing.
            val ended = withTimeoutOrNull(5.seconds) { target.join() }

            assertNotNull(ended, "the stall tier must still reap a live-path round, or V4-116 is disabled")
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "expected the stall tier's Idle sentinel, got $fired")
            assertEquals(200L, (fired as WatchdogFired.Idle).limitMs, "the ARMED limit, not streamIdle")
            assertNull(dog.held, "a tier whose breach is a heal must never be held by the pulse")

            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `the whole-turn cap ends a held round, and nothing else does`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(firstByteMs = 300, idleMs = 300, capMs = 1_200))
            val turn = launch { delay(10.seconds) }
            val cap = dog.launchTotalCap(this, turn)
            // A LIVE pulse for the whole turn: with the tier holding and the path never going quiet,
            // the cap is the only thing left that can end this.
            val poller = dog.launchIn(
                this,
                slot,
                turn,
                ClientFrameEmitted { false },
                openConnection(),
                PathEvidence.OPEN_CONNECTION,
            )
            val ended = withTimeoutOrNull(6.seconds) { turn.join() }

            assertNotNull(ended, "a held round must still be ended by the whole-turn cap")
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.TotalCap, "only the wall may end a held round, got $fired")
            assertNotNull(dog.held, "the tier must have probed and held before the wall arrived")

            poller.cancel()
            cap.cancel()
            slot.release()
        }
    }

    // FACT 4, the fallback's asymmetry, and the pair is the point: the two arms below differ ONLY in
    // what the probe answers, and they must land on opposite verdicts. A probe that refuses is real
    // evidence — every connection to that provider is failing, so waiting cannot produce a token —
    // while a probe that cannot RUN (DNS, its own timeout, a provider rate-limiting the probe but not
    // the stream) is inconclusive. Collapsing the two would turn a local network hiccup into a dead
    // turn, which is the class of mistake this whole row exists to remove.
    @Test
    fun `a refusing provider probe ends a round the socket alone would have held`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(firstByteMs = 300, idleMs = 300, capMs = 30_000))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(
                this,
                slot,
                target,
                ClientFrameEmitted { false },
                openConnection(),
                PathEvidence.OPEN_CONNECTION,
                ProviderProbe { false },
            )
            val ended = withTimeoutOrNull(5.seconds) { target.join() }

            assertNotNull(ended, "a refused path must end the round rather than hold it")
            assertTrue(dog.fired is WatchdogFired.Idle, "the refusal is what the tier judged on: ${dog.fired}")
            assertNull(dog.held, "a path the provider refused must not be held")

            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `a probe that cannot tell holds - only a definite refusal may reap`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(firstByteMs = 300, idleMs = 300, capMs = 30_000))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(
                this,
                slot,
                target,
                ClientFrameEmitted { false },
                openConnection(),
                PathEvidence.OPEN_CONNECTION,
                // A probe that could not get an answer reports TRUE by contract, so this arm is
                // indistinguishable from a reachable one BY DESIGN — that is the class the row
                // refuses to let reap. It does not throw: [ProviderProbe] answers, and the
                // production probe translates its own failures into this same answer where the
                // specific exception types are known (see UpstreamTransport.reachabilityProbe).
                ProviderProbe { true },
            )
            delay(POLLS_SETTLE_MS)

            assertNull(dog.fired, "a probe that could not tell is not evidence of death")
            assertTrue(target.isActive, "the round must still be running")
            assertNotNull(dog.held, "an unanswered probe must fall back to holding")

            poller.cancel()
            slot.release()
        }
    }
}
